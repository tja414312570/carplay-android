package com.carplay.sender

/** Ownership derives from requests and notifications; packets being sent are not ownership proof. */
class SessionModes {
    private data class Resource(var owner: Long = 1, var permanent: Long = 1)
    private val resources = mutableMapOf(1L to Resource(), 2L to Resource(2,2))
    @Synchronized fun change(command: Map<*, *>): Map<String, Any> {
        require(command["type"] == "changeModes")
        val parameters = command["params"] as? Map<*, *> ?: error("changeModes 参数缺失")
        val requests = (parameters["resources"] as? List<*> ?: error("资源请求缺失")).map { item ->
            val entry = item as? Map<*, *> ?: error("资源请求无效")
            val id = (entry["resourceID"] as? Number)?.toLong() ?: error("资源编号缺失")
            require(id in resources)
            val type = (entry["transferType"] as? Number)?.toInt() ?: error("资源转移类型缺失")
            require(type in 1..4)
            id to type
        }
        for ((id,type) in requests) {
            val resource = resources.getValue(id)
            when (type) {
                1 -> { resource.owner = 2; resource.permanent = 2 }
                2 -> { resource.owner = 1; resource.permanent = 1 }
                3 -> resource.owner = 2
                4 -> resource.owner = resource.permanent
                else -> error("不支持的资源转移类型")
            }
        }
        return snapshot()
    }
    @Synchronized fun snapshot(): Map<String, Any> = mapOf("type" to "modesChanged", "params" to mapOf(
        "resources" to resources.map { (id, resource) -> mapOf("resourceID" to id, "entity" to resource.owner, "permanentEntity" to resource.permanent) }
    ))
    @Synchronized fun ownsScreen() = resources.getValue(1).owner == 1L
    @Synchronized fun ownsAudio() = resources.getValue(2).owner == 1L
    @Synchronized fun audio(phone: Boolean) {
        resources.getValue(2).apply { owner = if (phone) 1 else 2; permanent = owner }
    }
}
