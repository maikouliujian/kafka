/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * The set of requests which have been sent or are being sent but haven't yet received a response
 */
final class InFlightRequests {

	// 最大允许的正在发送的ClientRequest数量，通过参数max.in.flight.requests.per.connection配置
    private final int maxInFlightRequestsPerConnection;
	// 键为发送请求的目的Node节点的id，值为发送到该节点的ClientRequest请求的队列
    private final Map<String, Deque<ClientRequest>> requests = new HashMap<String, Deque<ClientRequest>>();

    public InFlightRequests(int maxInFlightRequestsPerConnection) {
        this.maxInFlightRequestsPerConnection = maxInFlightRequestsPerConnection;
    }

    /**
     * Add the given request to the queue for the connection it was directed to
     */
    public void add(ClientRequest request) {
        /**
         * 以ClientRequest对象中封装的RequestSend的destination为键从requests中获取对应的Deque
         * 这个destination的值其实是当时发送请求的目的Node节点的id，可以回顾下面的方法：
		 * {@link NetworkClient.DefaultMetadataUpdater#maybeUpdate}
         * requests的结构是Map<String, Deque<ClientRequest>>
         */
        Deque<ClientRequest> reqs = this.requests.get(request.request().destination());
        // 如果没有获取到就创建一个
        if (reqs == null) {
            reqs = new ArrayDeque<>();
            this.requests.put(request.request().destination(), reqs);
        }
        // 将ClientRequest对象添加到对应的Deque中
        reqs.addFirst(request);
    }

    /**
     * Get the request queue for the given node
     */
    private Deque<ClientRequest> requestQueue(String node) {
        Deque<ClientRequest> reqs = requests.get(node);
        if (reqs == null || reqs.isEmpty())
            throw new IllegalStateException("Response from server for which there are no in-flight requests.");
        return reqs;
    }

    /**
     * Get the oldest request (the one that that will be completed next) for the given node
     */
    public ClientRequest completeNext(String node) {
        return requestQueue(node).pollLast();
    }

    /**
     * Get the last request we sent to the given node (but don't remove it from the queue)
     * @param node The node id
     */
    public ClientRequest lastSent(String node) {
        return requestQueue(node).peekFirst();
    }

    /**
     * Complete the last request that was sent to a particular node.
     * @param node The node the request was sent to
     * @return The request
     */
    public ClientRequest completeLastSent(String node) {
        return requestQueue(node).pollFirst();
    }

    /**
     * Can we send more requests to this node?
     *
     * 判断是否可以向某个Node发送更多的请求
     * 主要通过Deque<ClientRequest>队列的情况来判断
     * 该队列中存放了所有已经发送但没有收到响应的ClientRequest
     * 
     * @param node Node in question
     * @return true iff we have no requests still being sent to the given node
     */
    public boolean canSendMore(String node) {
        Deque<ClientRequest> queue = requests.get(node);
        /**
         * queue为null，
         * 或者queue内没有元素
         * 或者queue的队首元素已经完成了请求，
         * 或者queue内元素个数没有达到maxInFlightRequestsPerConnection（max.in.flight.requests.per.connection）指定的数量
         */
        return queue == null || queue.isEmpty() ||
               (queue.peekFirst().request().completed() && queue.size() < this.maxInFlightRequestsPerConnection);
    }

    /**
     * Return the number of inflight requests directed at the given node
     * @param node The node
     * @return The request count.
     */
    public int inFlightRequestCount(String node) {
        Deque<ClientRequest> queue = requests.get(node);
        return queue == null ? 0 : queue.size();
    }

    /**
     * Count all in-flight requests for all nodes
     */
    public int inFlightRequestCount() {
        int total = 0;
        for (Deque<ClientRequest> deque : this.requests.values())
            total += deque.size();
        return total;
    }

    /**
     * Clear out all the in-flight requests for the given node and return them
     * 
     * @param node The node
     * @return All the in-flight requests for that node that have been removed
     */
    public Iterable<ClientRequest> clearAll(String node) {
        Deque<ClientRequest> reqs = requests.get(node);
        if (reqs == null) {
            return Collections.emptyList();
        } else {
            return requests.remove(node);
        }
    }

    /**
     * Returns a list of nodes with pending inflight request, that need to be timed out
     *
     * @param now current time in milliseconds
     * @param requestTimeout max time to wait for the request to be completed
     * @return list of nodes
     */
    // 获取超时的请求所到达的Node的Id
    public List<String> getNodesWithTimedOutRequests(long now, int requestTimeout) {
        List<String> nodeIds = new LinkedList<String>();
        for (String nodeId : requests.keySet()) {
            if (inFlightRequestCount(nodeId) > 0) {
                ClientRequest request = requests.get(nodeId).peekLast();
                // 获取距离发送的时间
                long timeSinceSend = now - request.sendTimeMs();
                // 判断是否超时
                if (timeSinceSend > requestTimeout) {
                    nodeIds.add(nodeId);
                }
            }
        }

        return nodeIds;
    }
}
