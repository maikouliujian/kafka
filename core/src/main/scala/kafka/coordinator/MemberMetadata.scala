/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.coordinator

import java.util

import kafka.utils.nonthreadsafe

import scala.collection.Map


case class MemberSummary(memberId: String,
                         clientId: String,
                         clientHost: String,
                         metadata: Array[Byte],
                         assignment: Array[Byte])

/**
 * Member metadata contains the following metadata:
 *
 * Heartbeat metadata:
 * 1. negotiated heartbeat session timeout
 * 2. timestamp of the latest heartbeat
 *
 * Protocol metadata:
 * 1. the list of supported protocols (ordered by preference)
 * 2. the metadata associated with each protocol
 *
 * In addition, it also contains the following state information:
 *
 * 1. Awaiting rebalance callback: when the group is in the prepare-rebalance state,
 *                                 its rebalance callback will be kept in the metadata if the
 *                                 member has sent the join group request
 * 2. Awaiting sync callback: when the group is in the awaiting-sync state, its sync callback
 *                            is kept in metadata until the leader provides the group assignment
 *                            and the group transitions to stable
  *
  * GroupCoordinator用于记录消费者的元数据的类
  *
  * @param memberId 对应消费者的ID，由服务端的GroupCoordinator分配
  * @param groupId 记录消费者所在的Consumer Group的ID
  * @param clientId 消费者客户端ID，与memberId不同
  * @param clientHost 消费者客户端的Host信息
  * @param sessionTimeoutMs
  * @param supportedProtocols 对应消费者支持的PartitionAssignor
 */
@nonthreadsafe
private[coordinator] class MemberMetadata(val memberId: String,
                                          val groupId: String,
                                          val clientId: String,
                                          val clientHost: String,
                                          val sessionTimeoutMs: Int,
                                          var supportedProtocols: List[(String, Array[Byte])]) {

  // 记录分配给当前Member的分区信息
  var assignment: Array[Byte] = Array.empty[Byte]
  // 与JoinGroupRequest相关的回调函数
  var awaitingJoinCallback: JoinGroupResult => Unit = null
  // 与SyncGroupRequest相关的回调函数
  var awaitingSyncCallback: (Array[Byte], Short) => Unit = null
  // 最后一次收到心跳消息的时间戳
  var latestHeartbeat: Long = -1
  // 标识对应的消费者是否已经离开了Consumer Group
  var isLeaving: Boolean = false

  // 当前Member支持的PartitionAssignor协议集合
  def protocols = supportedProtocols.map(_._1).toSet

  /**
   * Get metadata corresponding to the provided protocol.
   */
  def metadata(protocol: String): Array[Byte] = {
    // 匹配查找传入的protocol对应的PartitionAssignor metadata
    supportedProtocols.find(_._1 == protocol) match {
      case Some((_, metadata)) => metadata
      case None => // 未查找到会返回IllegalArgumentException异常
        throw new IllegalArgumentException("Member does not support protocol")
    }
  }

  /**
   * Check if the provided protocol metadata matches the currently stored metadata.
    *
    * 检查当前MemberMetadata支持的PartitionAssignor是否与传入的protocols中的PartitionAssignor匹配
   */
  def matches(protocols: List[(String, Array[Byte])]): Boolean = {
    // 大小不一样，必然是不匹配的
    if (protocols.size != this.supportedProtocols.size)
      return false

    // 遍历进行一一对比，如有不同则是不匹配的
    for (i <- 0 until protocols.size) {
      val p1 = protocols(i)
      val p2 = supportedProtocols(i)
      if (p1._1 != p2._1 || !util.Arrays.equals(p1._2, p2._2))
        return false
    }
    return true
  }

  def summary(protocol: String): MemberSummary = {
    MemberSummary(memberId, clientId, clientHost, metadata(protocol), assignment)
  }

  def summaryNoMetadata(): MemberSummary = {
    MemberSummary(memberId, clientId, clientHost, Array.empty[Byte], Array.empty[Byte])
  }

  /**
   * Vote for one of the potential group protocols. This takes into account the protocol preference as
   * indicated by the order of supported protocols and returns the first one also contained in the set
    * 从给定候选的PartitionAssignor中选择消费者支持的PartitionAssignor
   */
  def vote(candidates: Set[String]): String = {
    // 遍历客户端支持的PartitionAssignor，根据指定的candidates包含的PartitionAssignor返回支持的PartitionAssignor
    supportedProtocols.find({ case (protocol, _) => candidates.contains(protocol)}) match {
      case Some((protocol, _)) => protocol
      case None =>
        throw new IllegalArgumentException("Member does not support any of the candidate protocols")
    }
  }

  override def toString = {
    "[%s,%s,%s,%d]".format(memberId, clientId, clientHost, sessionTimeoutMs)
  }
}
