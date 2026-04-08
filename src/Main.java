import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            return;
        }
        MatrixNet matrixNet = new MatrixNet();
        try (BufferedReader reader = new BufferedReader(new FileReader(args[0]), 32768);
                BufferedWriter writer = new BufferedWriter(new FileWriter(args[1]), 32768)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                matrixNet.process(line, writer);
            }
        }
    }

    // Core simulation engine for MatrixNet
    private static class MatrixNet {
        private static final int MAX_HOSTS_INIT = 10000;
        // Host data using parallel arrays for cache efficiency
        private String[] hostIds = new String[MAX_HOSTS_INIT];
        private int[] hostClearance = new int[MAX_HOSTS_INIT];
        private int hostCount = 0;
        // Custom Hash Map for Host ID (String) -> Index (int)
        // Uses separate chaining for collision resolution.
        private final HostMap hostMap = new HostMap();

        private static final int MAX_EDGES_INIT = 50000;
        // Adjacency list (Linked Forward Star representation)
        // head[u] points to the first edge index for host u
        private int[] head;
        // next[e] points to the next edge index in the list
        private int[] next = new int[MAX_EDGES_INIT];
        private int[] to = new int[MAX_EDGES_INIT];
        private int[] latency = new int[MAX_EDGES_INIT];
        private int[] bandwidth = new int[MAX_EDGES_INIT];
        private int[] firewall = new int[MAX_EDGES_INIT];
        private boolean[] sealed = new boolean[MAX_EDGES_INIT];
        private int edgeCount = 0;

        // Maps edge e to its reverse edge partner
        private int[] edgePartner = new int[MAX_EDGES_INIT];

        // Lookup for existing connections between u and v
        private final ConnectionMap connectionMap = new ConnectionMap();

        private long totalClearance = 0;
        private long unsealedBandwidthSum = 0;
        private int unsealedBackdoorCount = 0;

        private boolean componentsDirty = true;
        private int cachedComponentCount = 0;
        private boolean graphInfoDirty = true;
        private GraphInfo cachedGraphInfo = new GraphInfo();

        // Dijkstra structures (Pareto frontier states)
        private int[] bestHead;
        private int[] bestRunId;
        private BestStatePool bestPool;
        private int routeSearchId = 0;

        // BFS and pooling structures for graph traversal
        private int[] visitedArray = new int[MAX_HOSTS_INIT];
        private int visitedToken = 0;
        private int[] bfsQueue = new int[MAX_HOSTS_INIT];
        private final StringBuilder logBuffer = new StringBuilder(1024);

        private final PrimitiveRouteStateHeap pq = new PrimitiveRouteStateHeap();
        private final LineParser parser = new LineParser();

        MatrixNet() {
            head = new int[MAX_HOSTS_INIT];
            head = new int[MAX_HOSTS_INIT];
            for (int i = 0; i < head.length; i++)
                head[i] = -1;
        }

        // Host ranking for lexicographical tie-breaking
        private int[] hostRank;
        private boolean hostsDirtyRanks = true;

        void process(String line, BufferedWriter writer) throws IOException {
            parser.reset(line);
            String command = parser.nextString();
            if (command == null)
                return;

            switch (command) {
                case "spawn_host":
                    handleSpawnHost(writer);
                    break;
                case "link_backdoor":
                    handleLinkBackdoor(writer);
                    break;
                case "seal_backdoor":
                    handleSealBackdoor(writer);
                    break;
                case "trace_route":
                    handleTraceRoute(writer);
                    break;
                case "scan_connectivity":
                    handleScanConnectivity(writer);
                    break;
                case "simulate_breach":
                    handleSimulateBreach(writer);
                    break;
                case "oracle_report":
                    handleOracleReport(writer);
                    break;
            }
        }

        private void handleSpawnHost(BufferedWriter writer) throws IOException {
            String id = parser.nextString();
            int clearance = parser.nextInt();

            if (!isValidHostId(id) || hostMap.contains(id)) {
                writer.write("Some error occurred in spawn_host.");
                writer.newLine();
                return;
            }
            ensureHostCapacity(hostCount + 1);
            int u = hostCount++;
            hostIds[u] = id;
            hostClearance[u] = clearance;
            hostMap.put(id, u);
            head[u] = -1; // Initialize edge list as empty

            totalClearance += clearance;
            // Mark graph properties as dirty since node count changed
            markGraphDirty();
            hostsDirtyRanks = true; // Trigger re-ranking
            logBuffer.setLength(0);
            logBuffer.append("Spawned host ").append(id).append(" with clearance level ").append(clearance).append(".");
            writer.append(logBuffer);
            writer.newLine();
        }

        private void handleLinkBackdoor(BufferedWriter writer) throws IOException {
            if (!parser.nextToken()) {
                writer.write("Some error occurred in link_backdoor.");
                writer.newLine();
                return;
            }
            int u = hostMap.get(parser.line, parser.tokenStart, parser.tokenLen);
            if (!parser.nextToken()) {
                writer.write("Some error occurred in link_backdoor.");
                writer.newLine();
                return;
            }
            int v = hostMap.get(parser.line, parser.tokenStart, parser.tokenLen);

            if (u == -1 || v == -1 || u == v) {
                writer.write("Some error occurred in link_backdoor.");
                writer.newLine();
                return;
            }
            long key = connectionKey(u, v);
            if (connectionMap.contains(key)) {
                writer.write("Some error occurred in link_backdoor.");
                writer.newLine();
                return;
            }
            int lat = parser.nextInt();
            int bw = parser.nextInt();
            int fw = parser.nextInt();

            addEdge(u, v, lat, bw, fw);

            unsealedBackdoorCount++;
            unsealedBandwidthSum += bw;
            markGraphDirty(); // Connectivity might change
            logBuffer.setLength(0);
            logBuffer.append("Linked ").append(hostIds[u]).append(" <-> ").append(hostIds[v])
                    .append(" with latency ").append(lat).append("ms, bandwidth ").append(bw)
                    .append("Mbps, firewall ").append(fw).append(".");
            writer.append(logBuffer);
            writer.newLine();
        }

        private void addEdge(int u, int v, int lat, int bw, int fw) {
            ensureEdgeCapacity(edgeCount + 2);

            int e1 = edgeCount++;
            to[e1] = v;
            latency[e1] = lat;
            bandwidth[e1] = bw;
            firewall[e1] = fw;
            sealed[e1] = false;
            next[e1] = head[u];
            head[u] = e1;

            int e2 = edgeCount++;
            to[e2] = u;
            latency[e2] = lat;
            bandwidth[e2] = bw;
            firewall[e2] = fw;
            sealed[e2] = false;
            next[e2] = head[v];
            head[v] = e2;

            edgePartner[e1] = e2;
            edgePartner[e2] = e1;

            connectionMap.put(connectionKey(u, v), e1);
        }

        private void handleSealBackdoor(BufferedWriter writer) throws IOException {
            if (!parser.nextToken()) {
                writer.write("Some error occurred in seal_backdoor.");
                writer.newLine();
                return;
            }
            int u = hostMap.get(parser.line, parser.tokenStart, parser.tokenLen);
            if (!parser.nextToken()) {
                writer.write("Some error occurred in seal_backdoor.");
                writer.newLine();
                return;
            }
            int v = hostMap.get(parser.line, parser.tokenStart, parser.tokenLen);

            if (u == -1 || v == -1) {
                writer.write("Some error occurred in seal_backdoor.");
                writer.newLine();
                return;
            }
            long key = connectionKey(u, v);
            int e1 = connectionMap.get(key);
            if (e1 == -1) {
                writer.write("Some error occurred in seal_backdoor.");
                writer.newLine();
                return;
            }
            int e2 = edgePartner[e1];

            if (sealed[e1]) {
                sealed[e1] = false;
                sealed[e2] = false;
                unsealedBackdoorCount++;
                unsealedBandwidthSum += bandwidth[e1];
                logBuffer.setLength(0);
                logBuffer.append("Backdoor ").append(hostIds[u]).append(" <-> ").append(hostIds[v])
                        .append(" unsealed.");
                writer.append(logBuffer);
            } else {
                sealed[e1] = true;
                sealed[e2] = true;
                unsealedBackdoorCount--;
                unsealedBandwidthSum -= bandwidth[e1];
                logBuffer.setLength(0);
                logBuffer.append("Backdoor ").append(hostIds[u]).append(" <-> ").append(hostIds[v]).append(" sealed.");
                writer.append(logBuffer);
            }
            writer.newLine();
            markGraphDirty();
        }

        private void handleTraceRoute(BufferedWriter writer) throws IOException {
            if (!parser.nextToken()) {
                writer.write("Some error occurred in trace_route.");
                writer.newLine();
                return;
            }
            int source = hostMap.get(parser.line, parser.tokenStart, parser.tokenLen);
            if (!parser.nextToken()) {
                writer.write("Some error occurred in trace_route.");
                writer.newLine();
                return;
            }
            int dest = hostMap.get(parser.line, parser.tokenStart, parser.tokenLen);

            if (source == -1 || dest == -1) {
                writer.write("Some error occurred in trace_route.");
                writer.newLine();
                return;
            }
            if (source == dest) {
                logBuffer.setLength(0);
                logBuffer.append("Optimal route ").append(hostIds[source]).append(" -> ").append(hostIds[dest])
                        .append(": ").append(hostIds[source]).append(" (Latency = 0ms)");
                writer.append(logBuffer);
                writer.newLine();
                return;
            }
            int minBw = parser.nextInt();
            int lambda = parser.nextInt();

            RouteResult result = findRoute(source, dest, minBw, lambda);
            if (!result.found) {
                logBuffer.setLength(0);
                logBuffer.append("No route found from ").append(hostIds[source]).append(" to ").append(hostIds[dest]);
                writer.append(logBuffer);
            } else {
                logBuffer.setLength(0);
                logBuffer.append("Optimal route ").append(hostIds[source]).append(" -> ").append(hostIds[dest])
                        .append(": ");
                for (int i = 0; i < result.path.size(); i++) {
                    if (i > 0)
                        logBuffer.append(" -> ");
                    logBuffer.append(result.path.get(i));
                }
                logBuffer.append(" (Latency = ").append(result.latency).append("ms)");
                writer.append(logBuffer);
            }
            writer.newLine();
        }

        // Multi-objective Dijkstra: minimizes cost (lambda-adjusted latency),
        // then hop count, then lexicographical order.
        private RouteResult findRoute(int source, int dest, int minBw, int lambda) {
            updateHostRanks();

            // Ensure heap capacity based on heuristic (4 states per node avg)
            int estimatedStates = Math.max(4096, hostCount * 4);
            pq.ensureCapacity(estimatedStates);
            pq.reset();

            // Resize BestStatePool tracking structures if needed
            if (bestHead == null || bestHead.length < hostCount) {
                int cap = Math.max(hostCount, 1024);
                bestHead = new int[cap];
                for (int i = 0; i < cap; i++)
                    bestHead[i] = -1;
                bestRunId = new int[cap];
            }
            if (bestPool == null) {
                int initialPoolCap = Math.max(16384, hostCount * 4);
                bestPool = new BestStatePool(initialPoolCap);
            } else {
                bestPool.reset();
            }

            routeSearchId++;

            // Initialize start state: source node, 0 cost, 0 edges
            int startIdx = pq.allocState(source, 0L, 0, -1);

            // Lazy initialization of bestHead for current search run
            if (bestRunId[source] != routeSearchId) {
                bestHead[source] = -1;
                bestRunId[source] = routeSearchId;
            }
            // Add (0,0) as the initial best state for source
            bestHead[source] = bestPool.alloc(0L, 0, bestHead[source]);

            // Add to Priority Queue
            pq.add(startIdx);

            long bestCost = Long.MAX_VALUE;
            int bestStateIdx = -1;
            ArrayList<String> bestPath = null;
            int bestEdges = Integer.MAX_VALUE;

            long maxEdgesForLambda = lambda > 0 ? Long.MAX_VALUE / lambda : Long.MAX_VALUE;

            while (!pq.isEmpty()) {
                int stateIdx = pq.poll();

                int u = pq.poolU[stateIdx];
                long cost = pq.poolCost[stateIdx];
                int edges = pq.poolEdges[stateIdx];

                // Pruning: if current path is strictly worse than best found so far
                if (cost > bestCost || (cost == bestCost && edges > bestEdges))
                    continue;

                boolean dominated = false;
                if (bestRunId[u] == routeSearchId) {
                    for (int i = bestHead[u]; i != -1; i = bestPool.next[i]) {
                        long c = bestPool.costs[i];
                        int e = bestPool.edges[i];
                        // Domination check: if existing state has <= cost AND <= edges
                        if (c <= cost && e <= edges && (c < cost || e < edges)) {
                            dominated = true;
                            break;
                        }
                    }
                }
                if (dominated)
                    continue;

                if (u == dest) {
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestEdges = edges;
                        bestStateIdx = stateIdx;
                        bestPath = null;
                    } else {

                        if (edges < bestEdges) {
                            bestEdges = edges; // Found path with fewer hops
                            bestStateIdx = stateIdx;
                            bestPath = null;
                        } else if (edges == bestEdges) {
                            // Lexicographical tie-break needed
                            ArrayList<String> candidatePath = reconstructPath(stateIdx, pq);
                            if (bestPath == null)
                                bestPath = reconstructPath(bestStateIdx, pq);
                            if (comparePaths(candidatePath, bestPath) < 0) {
                                bestStateIdx = stateIdx;
                                bestPath = candidatePath;
                            }
                        }
                    }
                    continue;
                }

                for (int e = head[u]; e != -1; e = next[e]) {
                    if (sealed[e] || bandwidth[e] < minBw)
                        continue;
                    if (hostClearance[u] < firewall[e])
                        continue;

                    long lambdaPenalty = 0;
                    if (lambda != 0) {
                        if (edges > maxEdgesForLambda)
                            continue;
                        lambdaPenalty = (long) lambda * edges;
                    }
                    if (lambdaPenalty > Long.MAX_VALUE - latency[e])
                        continue;
                    long delta = lambdaPenalty + latency[e];
                    if (cost > Long.MAX_VALUE - delta)
                        continue;
                    long newCost = cost + delta;

                    if (newCost > bestCost)
                        continue;

                    int v = to[e];
                    int newEdges = edges + 1;

                    // Lazy init for neighbor v
                    if (bestRunId[v] != routeSearchId) {
                        bestHead[v] = -1;
                        bestRunId[v] = routeSearchId;
                    }

                    // Pre-check domination for v before allocating state
                    boolean vDominated = false;
                    for (int i = bestHead[v]; i != -1; i = bestPool.next[i]) {
                        long c = bestPool.costs[i];
                        int ed = bestPool.edges[i];
                        if (c <= newCost && ed <= newEdges && (c < newCost || ed < newEdges)) {
                            vDominated = true;
                            break;
                        }
                    }
                    if (vDominated)
                        continue;

                    int currentHead = bestHead[v];
                    int prev = -1;
                    int curr = currentHead;
                    while (curr != -1) {
                        long c = bestPool.costs[curr];
                        int ed = bestPool.edges[curr];
                        int nxt = bestPool.next[curr];

                        if (newCost <= c && newEdges <= ed && (newCost < c || newEdges < ed)) {
                            if (prev == -1) {
                                bestHead[v] = nxt;
                            } else {
                                bestPool.next[prev] = nxt;
                            }
                            curr = nxt;
                        } else {
                            prev = curr;
                            curr = nxt;
                        }
                    }

                    int nextStateIdx = pq.allocState(v, newCost, newEdges, stateIdx);
                    bestHead[v] = bestPool.alloc(newCost, newEdges, bestHead[v]);
                    pq.add(nextStateIdx);
                }
            }

            if (bestStateIdx == -1)
                return RouteResult.notFound();
            if (bestPath == null)
                bestPath = reconstructPath(bestStateIdx, pq);
            return new RouteResult(true, bestCost, bestPath);
        }

        private ArrayList<String> reconstructPath(int stateIdx, PrimitiveRouteStateHeap pq) {
            FastDeque<String> reversed = new FastDeque<>();
            int current = stateIdx;
            while (current != -1) {
                reversed.addFirst(hostIds[pq.poolU[current]]);
                current = pq.poolPrev[current];
            }
            ArrayList<String> path = new ArrayList<>(reversed.size());
            while (!reversed.isEmpty()) {
                path.add(reversed.poll());
            }
            return path;
        }

        private int comparePaths(ArrayList<String> left, ArrayList<String> right) {
            int len = Math.min(left.size(), right.size());
            for (int i = 0; i < len; i++) {
                int cmp = left.get(i).compareTo(right.get(i));
                if (cmp != 0)
                    return cmp;
            }
            return Integer.compare(left.size(), right.size());
        }

        private void handleScanConnectivity(BufferedWriter writer) throws IOException {
            if (hostCount <= 1) {
                writer.write("Network is fully connected.");
                writer.newLine();
                return;
            }
            int components = getComponentCount();

            if (components == 1) {
                writer.write("Network is fully connected.");
            } else {
                logBuffer.setLength(0);
                logBuffer.append("Network has ").append(components).append(" disconnected components.");
                writer.append(logBuffer);
            }
            writer.newLine();
        }

        private void handleSimulateBreach(BufferedWriter writer) throws IOException {
            if (!parser.nextToken()) {
                writer.write("Some error occurred in simulate_breach.");
                writer.newLine();
                return;
            }
            int a = hostMap.get(parser.line, parser.tokenStart, parser.tokenLen);

            boolean hasArg2 = parser.nextToken();

            if (!hasArg2) {
                if (a == -1) {
                    writer.write("Some error occurred in simulate_breach.");
                    writer.newLine();
                    return;
                }
                int originalComponents = getComponentCount();
                int componentsAfter = countComponents(a, -1);
                if (componentsAfter > originalComponents) {
                    logBuffer.setLength(0);
                    logBuffer.append("Host ").append(hostIds[a]).append(" IS an articulation point.");
                    writer.append(logBuffer);
                    writer.newLine();
                    logBuffer.setLength(0);
                    logBuffer.append("Failure results in ").append(componentsAfter).append(" disconnected components.");
                    writer.append(logBuffer);
                } else {
                    logBuffer.setLength(0);
                    logBuffer.append("Host ").append(hostIds[a])
                            .append(" is NOT an articulation point. Network remains the same.");
                    writer.append(logBuffer);
                }
                writer.newLine();
                return;
            }

            int b = hostMap.get(parser.line, parser.tokenStart, parser.tokenLen);
            if (a == -1 || b == -1) {
                writer.write("Some error occurred in simulate_breach.");
                writer.newLine();
                return;
            }
            long key = connectionKey(a, b);
            int e = connectionMap.get(key);
            if (e == -1 || sealed[e]) {
                writer.write("Some error occurred in simulate_breach.");
                writer.newLine();
                return;
            }
            int originalComponents = getComponentCount();
            int componentsAfter = countComponents(-1, e);
            if (componentsAfter > originalComponents) {
                logBuffer.setLength(0);
                logBuffer.append("Backdoor ").append(hostIds[a]).append(" <-> ").append(hostIds[b])
                        .append(" IS a bridge.");
                writer.append(logBuffer);
                writer.newLine();
                logBuffer.setLength(0);
                logBuffer.append("Failure results in ").append(componentsAfter).append(" disconnected components.");
                writer.append(logBuffer);
            } else {
                logBuffer.setLength(0);
                logBuffer.append("Backdoor ").append(hostIds[a]).append(" <-> ").append(hostIds[b])
                        .append(" is NOT a bridge. Network remains the same.");
                writer.append(logBuffer);
            }
            writer.newLine();
        }

        private void handleOracleReport(BufferedWriter writer) throws IOException {
            GraphInfo info = getGraphInfo();
            writer.write("--- Resistance Network Report ---");
            writer.newLine();
            logBuffer.setLength(0);
            logBuffer.append("Total Hosts: ").append(hostCount);
            writer.append(logBuffer);
            writer.newLine();
            logBuffer.setLength(0);
            logBuffer.append("Total Unsealed Backdoors: ").append(unsealedBackdoorCount);
            writer.append(logBuffer);
            writer.newLine();
            boolean connected = hostCount <= 1 || info.components == 1;
            writer.write("Network Connectivity: " + (connected ? "Connected" : "Disconnected"));
            writer.newLine();
            logBuffer.setLength(0);
            logBuffer.append("Connected Components: ").append(info.components);
            writer.append(logBuffer);
            writer.newLine();
            writer.write("Contains Cycles: " + (info.hasCycle ? "Yes" : "No"));
            writer.newLine();
            logBuffer.setLength(0);
            logBuffer.append("Average Bandwidth: ").append(formatAverage(unsealedBandwidthSum, unsealedBackdoorCount))
                    .append("Mbps");
            writer.append(logBuffer);
            writer.newLine();
            logBuffer.setLength(0);
            logBuffer.append("Average Clearance Level: ").append(formatAverage(totalClearance, hostCount));
            writer.append(logBuffer);
            writer.newLine();
        }

        // Formats the average value to 1 decimal place, manually rounding halves up.
        private String formatAverage(long sum, int count) {
            if (count == 0)
                return "0.0";
            double avg = (double) sum / count;
            long rounded = Math.round(avg * 10);
            return (rounded / 10) + "." + (rounded % 10);
        }

        private GraphInfo analyzeGraph() {
            int size = hostCount;
            // Check if visitedArray needs resizing to match host count
            if (visitedArray.length < size) {
                int newCap = Math.max(visitedArray.length * 2, size);
                visitedArray = new int[newCap];
                bfsQueue = new int[newCap];
            }
            // Increment token to invalidate previous visits without O(N) clear
            visitedToken++;

            int[] parent = new int[size];
            for (int i = 0; i < size; i++)
                parent[i] = -1;

            GraphInfo info = new GraphInfo();
            int[] stack = new int[size];
            int top = 0;

            for (int i = 0; i < hostCount; i++) {
                if (visitedArray[i] == visitedToken)
                    continue;
                visitedArray[i] = visitedToken;
                info.components++;

                top = 0;
                stack[top++] = i;

                while (top > 0) {
                    int u = stack[--top];
                    for (int e = head[u]; e != -1; e = next[e]) {
                        if (sealed[e])
                            continue;
                        int v = to[e];
                        if (visitedArray[v] != visitedToken) {
                            visitedArray[v] = visitedToken;
                            parent[v] = u;
                            stack[top++] = v;
                        } else if (parent[u] != v) {
                            info.hasCycle = true;
                        }
                    }
                }
            }
            return info;
        }

        // Efficient BFS for counting connected components.
        // Can optionally 'skip' a specific host or edge to simulate failure.
        private int countComponents(int skipHost, int skipEdge) {
            if (hostCount == 0)
                return 0;
            if (hostCount == 1 && skipHost == 0)
                return 0;

            if (visitedArray.length < hostCount) {
                int newCap = Math.max(visitedArray.length * 2, hostCount);
                visitedArray = new int[newCap];
                bfsQueue = new int[newCap];
            }
            visitedToken++;
            int components = 0;

            int qHead = 0;
            int qTail = 0;

            int skipEdgePartner = -1;
            if (skipEdge != -1) {
                skipEdgePartner = edgePartner[skipEdge];
            }

            for (int i = 0; i < hostCount; i++) {
                if (i == skipHost || visitedArray[i] == visitedToken)
                    continue;
                components++;
                qHead = 0;
                qTail = 0;
                bfsQueue[qTail++] = i;
                visitedArray[i] = visitedToken;

                while (qHead < qTail) {
                    int u = bfsQueue[qHead++];
                    for (int e = head[u]; e != -1; e = next[e]) {
                        if (e == skipEdge || e == skipEdgePartner || sealed[e])
                            continue;
                        int v = to[e];
                        if (v == skipHost)
                            continue;
                        if (visitedArray[v] != visitedToken) {
                            visitedArray[v] = visitedToken;
                            bfsQueue[qTail++] = v;
                        }
                    }
                }
            }
            return components;
        }

        private int getComponentCount() {
            if (componentsDirty) {
                cachedComponentCount = countComponents(-1, -1);
                componentsDirty = false;
            }
            return cachedComponentCount;
        }

        private GraphInfo getGraphInfo() {
            if (graphInfoDirty) {
                cachedGraphInfo = analyzeGraph();
                graphInfoDirty = false;
                cachedComponentCount = cachedGraphInfo.components;
                componentsDirty = false;
            }
            return cachedGraphInfo;
        }

        private void markGraphDirty() {
            componentsDirty = true;
            graphInfoDirty = true;
        }

        private void ensureHostCapacity(int capacity) {
            if (capacity > hostIds.length) {
                int newCap = Math.max(hostIds.length * 2, capacity);
                String[] newHostIds = new String[newCap];
                System.arraycopy(hostIds, 0, newHostIds, 0, hostIds.length);
                hostIds = newHostIds;

                int[] newHostClearance = new int[newCap];
                System.arraycopy(hostClearance, 0, newHostClearance, 0, hostClearance.length);
                hostClearance = newHostClearance;

                int[] newHead = new int[newCap];
                System.arraycopy(head, 0, newHead, 0, head.length);

                for (int i = head.length; i < newCap; i++)
                    newHead[i] = -1;
                head = newHead;
            }
        }

        private void ensureEdgeCapacity(int capacity) {
            if (capacity > next.length) {
                int newCap = Math.max(next.length * 2, capacity);
                int[] newNext = new int[newCap];
                System.arraycopy(next, 0, newNext, 0, next.length);
                next = newNext;
                int[] newTo = new int[newCap];
                System.arraycopy(to, 0, newTo, 0, to.length);
                to = newTo;
                int[] newLatency = new int[newCap];
                System.arraycopy(latency, 0, newLatency, 0, latency.length);
                latency = newLatency;
                int[] newBandwidth = new int[newCap];
                System.arraycopy(bandwidth, 0, newBandwidth, 0, bandwidth.length);
                bandwidth = newBandwidth;
                int[] newFirewall = new int[newCap];
                System.arraycopy(firewall, 0, newFirewall, 0, firewall.length);
                firewall = newFirewall;
                boolean[] newSealed = new boolean[newCap];
                System.arraycopy(sealed, 0, newSealed, 0, sealed.length);
                sealed = newSealed;
                int[] newEdgePartner = new int[newCap];
                System.arraycopy(edgePartner, 0, newEdgePartner, 0, edgePartner.length);
                edgePartner = newEdgePartner;
            }
        }

        private static long connectionKey(int a, int b) {
            if (a < b)
                return (((long) a) << 32) ^ b;
            return (((long) b) << 32) ^ a;
        }

        private boolean isValidHostId(String id) {
            if (id == null || id.isEmpty())
                return false;
            int len = id.length();
            for (int i = 0; i < len; i++) {
                char c = id.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'))
                    return false;
            }
            return true;
        }

        private static class GraphInfo {
            int components = 0;
            boolean hasCycle = false;
        }

        private record RouteResult(boolean found, long latency, ArrayList<String> path) {
            static RouteResult notFound() {
                return new RouteResult(false, 0L, new ArrayList<>());
            }
        }

        // Pool for storing the Pareto frontier of best states for each node.
        // Each node maintains a linked list of non-dominated (Cost, Edges) pairs.
        private static class BestStatePool {
            long[] costs;
            int[] edges;
            int[] next;
            int ptr = 0;

            BestStatePool(int initialCap) {
                costs = new long[initialCap];
                edges = new int[initialCap];
                next = new int[initialCap];
            }

            int alloc(long cost, int edge, int nextIdx) {
                if (ptr >= costs.length)
                    resize();
                int idx = ptr++;
                costs[idx] = cost;
                edges[idx] = edge;
                next[idx] = nextIdx;
                return idx;
            }

            void reset() {
                ptr = 0;
            }

            private void resize() {
                int newCap = costs.length * 2;
                long[] newCosts = new long[newCap];
                System.arraycopy(costs, 0, newCosts, 0, costs.length);
                costs = newCosts;
                int[] newEdges = new int[newCap];
                System.arraycopy(edges, 0, newEdges, 0, edges.length);
                edges = newEdges;
                int[] newNext = new int[newCap];
                System.arraycopy(next, 0, newNext, 0, next.length);
                next = newNext;
            }
        }

        private static class FastDeque<T> {
            private Object[] elements;
            private int head;
            private int tail;

            FastDeque() {
                elements = new Object[16];
            }

            void addFirst(T e) {
                head = (head - 1) & (elements.length - 1);
                elements[head] = e;
                if (head == tail)
                    doubleCapacity();
            }

            @SuppressWarnings("unchecked")
            T poll() {
                int h = head;
                T result = (T) elements[h];
                if (result == null)
                    return null;
                elements[h] = null;
                head = (h + 1) & (elements.length - 1);
                return result;
            }

            boolean isEmpty() {
                return head == tail;
            }

            int size() {
                return (tail - head) & (elements.length - 1);
            }

            private void doubleCapacity() {
                int p = head;
                int n = elements.length;
                int r = n - p;
                int newCapacity = n << 1;
                if (newCapacity < 0)
                    throw new IllegalStateException();
                Object[] a = new Object[newCapacity];
                System.arraycopy(elements, p, a, 0, r);
                System.arraycopy(elements, 0, a, r, p);
                elements = a;
                head = 0;
                tail = n;
            }
        }

        // Custom RouteStateHeap using primitive arrays to avoid object overhead.
        // Acts as a Priority Queue for Dijkstra states.
        private class PrimitiveRouteStateHeap {
            int[] heap; // Stores indices into the 'pool' arrays
            int size = 0;

            // Pool arrays to store state data (U, Cost, Edges, PrevState)
            // This pooling mechanism drastically reduces GC pressure.
            int[] poolU;
            long[] poolCost;
            int[] poolEdges;
            int[] poolPrev;
            int poolPtr = 0;

            PrimitiveRouteStateHeap() {
                int initCap = 4096;
                poolU = new int[initCap];
                poolCost = new long[initCap];
                poolEdges = new int[initCap];
                poolPrev = new int[initCap];
                heap = new int[2048];
            }

            void ensureCapacity(int needed) {
                if (poolU.length < needed) {
                    int newCap = Math.max(poolU.length * 2, needed);
                    resizePool(newCap);
                }
                if (heap.length < needed) {
                    int newCap = Math.max(heap.length * 2, needed);
                    resizeHeap(newCap);
                }
            }

            int allocState(int u, long cost, int edges, int prev) {
                if (poolPtr >= poolU.length)
                    resizePool(poolU.length * 2);
                poolU[poolPtr] = u;
                poolCost[poolPtr] = cost;
                poolEdges[poolPtr] = edges;
                poolPrev[poolPtr] = prev;
                return poolPtr++;
            }

            void add(int stateIdx) {
                if (size >= heap.length)
                    resizeHeap(heap.length * 2);
                heap[size] = stateIdx;
                siftUp(size);
                size++;
            }

            int poll() {
                if (size == 0)
                    return -1;
                int res = heap[0];
                heap[0] = heap[size - 1];
                heap[size - 1] = -1;
                size--;
                if (size > 0)
                    siftDown(0);
                return res;
            }

            boolean isEmpty() {
                return size == 0;
            }

            private void resizePool(int newCap) {
                int[] newPoolU = new int[newCap];
                System.arraycopy(poolU, 0, newPoolU, 0, poolU.length);
                poolU = newPoolU;
                long[] newPoolCost = new long[newCap];
                System.arraycopy(poolCost, 0, newPoolCost, 0, poolCost.length);
                poolCost = newPoolCost;
                int[] newPoolEdges = new int[newCap];
                System.arraycopy(poolEdges, 0, newPoolEdges, 0, poolEdges.length);
                poolEdges = newPoolEdges;
                int[] newPoolPrev = new int[newCap];
                System.arraycopy(poolPrev, 0, newPoolPrev, 0, poolPrev.length);
                poolPrev = newPoolPrev;
            }

            private void resizeHeap(int newCap) {
                int[] newHeap = new int[newCap];
                System.arraycopy(heap, 0, newHeap, 0, heap.length);
                heap = newHeap;
            }

            private void siftUp(int index) {
                int item = heap[index];
                while (index > 0) {
                    int parentIdx = (index - 1) / 2;
                    int parent = heap[parentIdx];
                    if (compare(item, parent) < 0) {
                        heap[index] = parent;
                        index = parentIdx;
                    } else
                        break;
                }
                heap[index] = item;
            }

            private void siftDown(int index) {
                int item = heap[index];
                int half = size / 2;
                while (index < half) {
                    int childIdx = 2 * index + 1;
                    int child = heap[childIdx];
                    int rightIdx = childIdx + 1;
                    if (rightIdx < size) {
                        int right = heap[rightIdx];
                        if (compare(right, child) < 0) {
                            childIdx = rightIdx;
                            child = right;
                        }
                    }
                    if (compare(item, child) <= 0)
                        break;
                    heap[index] = child;
                    index = childIdx;
                }
                heap[index] = item;
            }

            private int compare(int idxA, int idxB) {
                long cA = poolCost[idxA];
                long cB = poolCost[idxB];
                if (cA < cB)
                    return -1;
                if (cA > cB)
                    return 1;

                int eA = poolEdges[idxA];
                int eB = poolEdges[idxB];
                if (eA < eB)
                    return -1;
                if (eA > eB)
                    return 1;

                return Integer.compare(hostRank[poolU[idxA]], hostRank[poolU[idxB]]);
            }

            void reset() {
                size = 0;
                poolPtr = 0;
            }
        }

        private void updateHostRanks() {
            if (!hostsDirtyRanks)
                return;
            int[] indices = new int[hostCount];
            for (int i = 0; i < hostCount; i++)
                indices[i] = i;

            quickSortIndices(indices, 0, hostCount - 1);

            if (hostRank == null || hostRank.length < hostCount) {
                hostRank = new int[Math.max(hostCount, hostIds.length)];
            }
            for (int i = 0; i < hostCount; i++) {
                hostRank[indices[i]] = i;
            }
            hostsDirtyRanks = false;
        }

        // Specialized QuickSort for host integers.
        // Sorts 'indices' array based on the String IDs in 'hostIds'.
        // Avoids Boxing/Unboxing of Integer objects.
        private void quickSortIndices(int[] indices, int low, int high) {
            if (low < high) {
                int pi = partitionIndices(indices, low, high);
                quickSortIndices(indices, low, pi - 1);
                quickSortIndices(indices, pi + 1, high);
            }
        }

        private int partitionIndices(int[] indices, int low, int high) {
            String pivot = hostIds[indices[high]];
            int i = (low - 1);
            for (int j = low; j < high; j++) {
                if (hostIds[indices[j]].compareTo(pivot) < 0) {
                    i++;
                    int temp = indices[i];
                    indices[i] = indices[j];
                    indices[j] = temp;
                }
            }
            int temp = indices[i + 1];
            indices[i + 1] = indices[high];
            indices[high] = temp;
            return i + 1;
        }

        private static class HostMap {
            private static final int INITIAL_CAPACITY = 2048;
            private static final float LOAD_FACTOR = 0.75f;
            private Entry[] table;
            private int size;
            private int threshold;

            HostMap() {
                table = new Entry[INITIAL_CAPACITY];
                threshold = (int) (INITIAL_CAPACITY * LOAD_FACTOR);
            }

            void put(String key, int value) {
                if (size >= threshold)
                    resize();
                int h = key.hashCode();
                h = h ^ (h >>> 16);
                int index = (h & 0x7FFFFFFF) % table.length;
                Entry head = table[index];
                Entry current = head;
                while (current != null) {
                    if (current.key.equals(key))
                        return;
                    current = current.next;
                }
                table[index] = new Entry(key, value, head);
                size++;
            }

            int get(String key) {
                int h = key.hashCode();
                h = h ^ (h >>> 16);
                int index = (h & 0x7FFFFFFF) % table.length;
                Entry current = table[index];
                while (current != null) {
                    if (current.key.equals(key))
                        return current.value;
                    current = current.next;
                }
                return -1;
            }

            int get(String context, int start, int len) {
                int h = 0;
                for (int i = 0; i < len; i++)
                    h = 31 * h + context.charAt(start + i);
                h = h ^ (h >>> 16);
                int index = (h & 0x7FFFFFFF) % table.length;
                Entry current = table[index];
                while (current != null) {
                    if (isEquals(current.key, context, start, len))
                        return current.value;
                    current = current.next;
                }
                return -1;
            }

            private boolean isEquals(String key, String context, int start, int len) {
                if (key.length() != len)
                    return false;
                for (int i = 0; i < len; i++)
                    if (key.charAt(i) != context.charAt(start + i))
                        return false;
                return true;
            }

            boolean contains(String key) {
                return get(key) != -1;
            }

            private void resize() {
                int newCap = table.length * 2;
                Entry[] newTable = new Entry[newCap];
                for (Entry e : table) {
                    while (e != null) {
                        Entry next = e.next;
                        int h = e.key.hashCode();
                        h = h ^ (h >>> 16);
                        int index = (h & 0x7FFFFFFF) % newCap;
                        e.next = newTable[index];
                        newTable[index] = e;
                        e = next;
                    }
                }
                table = newTable;
                threshold = (int) (newCap * LOAD_FACTOR);
            }

            static class Entry {
                final String key;
                final int value;
                Entry next;

                Entry(String key, int value, Entry next) {
                    this.key = key;
                    this.value = value;
                    this.next = next;
                }
            }
        }

        private static class ConnectionMap {
            private static final int INITIAL_CAPACITY = 2048;
            private static final float LOAD_FACTOR = 0.75f;
            private ConnEntry[] table;
            private int size;
            private int threshold;

            ConnectionMap() {
                table = new ConnEntry[INITIAL_CAPACITY];
                threshold = (int) (INITIAL_CAPACITY * LOAD_FACTOR);
            }

            void put(long key, int value) {
                if (size >= threshold)
                    resize();
                int h = Long.hashCode(key);
                h = h ^ (h >>> 16);
                int index = (h & 0x7FFFFFFF) % table.length;
                ConnEntry head = table[index];
                ConnEntry current = head;
                while (current != null) {
                    if (current.key == key)
                        return;
                    current = current.next;
                }
                table[index] = new ConnEntry(key, value, head);
                size++;
            }

            int get(long key) {
                int h = Long.hashCode(key);
                h = h ^ (h >>> 16);
                int index = (h & 0x7FFFFFFF) % table.length;
                ConnEntry current = table[index];
                while (current != null) {
                    if (current.key == key)
                        return current.value;
                    current = current.next;
                }
                return -1;
            }

            boolean contains(long key) {
                return get(key) != -1;
            }

            private void resize() {
                int newCap = table.length * 2;
                ConnEntry[] newTable = new ConnEntry[newCap];
                for (ConnEntry e : table) {
                    while (e != null) {
                        ConnEntry next = e.next;
                        int h = Long.hashCode(e.key);
                        h = h ^ (h >>> 16);
                        int index = (h & 0x7FFFFFFF) % newCap;
                        e.next = newTable[index];
                        newTable[index] = e;
                        e = next;
                    }
                }
                table = newTable;
                threshold = (int) (newCap * LOAD_FACTOR);
            }

            static class ConnEntry {
                final long key;
                final int value;
                ConnEntry next;

                ConnEntry(long key, int value, ConnEntry next) {
                    this.key = key;
                    this.value = value;
                    this.next = next;
                }
            }
        }

        // Helper class for parsing input lines without String.split()
        // Reduces temporary String allocation.
        private static class LineParser {
            String line;
            int pos;
            int len;
            int tokenStart;
            int tokenLen;

            void reset(String l) {
                line = l;
                pos = 0;
                len = l.length();
            }

            boolean nextToken() {
                skipWhitespace();
                if (pos >= len)
                    return false;
                tokenStart = pos;
                while (pos < len && line.charAt(pos) > ' ')
                    pos++;
                tokenLen = pos - tokenStart;
                return true;
            }

            String nextString() {
                if (!nextToken())
                    return null;
                return line.substring(tokenStart, tokenStart + tokenLen);
            }

            int nextInt() {
                skipWhitespace();
                if (pos >= len)
                    return 0;
                int val = 0;
                while (pos < len) {
                    char c = line.charAt(pos);
                    if (c <= ' ')
                        break;
                    val = val * 10 + (c - '0');
                    pos++;
                }
                return val;
            }

            void skipWhitespace() {
                while (pos < len && line.charAt(pos) <= ' ')
                    pos++;
            }
        }
    }
}
