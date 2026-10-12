package com.example.roleplay.ai.gguf

data class ModelProfile(
    val id: String,
    val name: String,
    val description: String = ""
)

/**
 * 推理服务与默认模型的登记处。
 *
 * 注意：请求体里**不发送 model 字段**，实际使用哪个模型由 PC 端
 * `scripts/model_switcher.py` + `servers.config.json` 决定。
 * 这里的名称直接取 gguf 文件名（界面中间省略显示），**必须与
 * `models/gguf` 目录里的实际文件一致**，否则切换器会空转。
 *
 * `id` 必须与 `scripts/servers.config.json` 里的 id 逐一对应。
 */
object ModelRegistry {
    const val SERVER_HOST = "192.168.1.100"
    const val INFER_PORT = 8080
    const val SWITCH_PORT = 8090

    /**
     * 默认模型：Qwen3.5-4B Q6_K。
     * 文风最华丽、情感浓度最高；代价是体积最大、生成最慢。
     */
    val defaultModel: ModelProfile = ModelProfile(
        id = "qwen3.5-4b-q6-k",
        name = "Qwen3.5-4B-Q6_K.gguf",
        description = "文风最华丽、情感浓度最高；三档里体积最大、生成最慢"
    )

    /**
     * 可切换的备选模型。
     *
     * 实际加载哪个由 PC 端决定：向 `http://<PC_IP>:8090/switch` POST
     * `{"id": "<上面的 id>"}`。列表必须与 `models/gguf` 目录里的实际文件一致。
     */
    val models: List<ModelProfile> = listOf(
        defaultModel,
        ModelProfile(
            id = "qwen3.5-4b-q4-k-m",
            name = "Qwen3.5-4B-Q4_K_M.gguf",
            description = "同 Q6 的速度更快一档，精度略低"
        ),
        ModelProfile(
            id = "qwen3.5-4b-q4-0",
            name = "Qwen3.5-4B-Q4_0.gguf",
            description = "体积最小的一档，加载最快，精度最低"
        )
    )
}
