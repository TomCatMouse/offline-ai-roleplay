package com.example.roleplay

import com.example.roleplay.ai.gguf.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 需求 #7：模型列表必须与 models/gguf 目录一致（3 个文件），
 * 且 id 与 PC 端 servers.config.json 一一对应。
 */
class ModelRegistryTest {

    /**
     * 与 [ModelRegistry.models] **逐项对齐**的真实量化后缀。
     *
     * 盘面变化史：Q5_K_M 的文件已不在 models/gguf 下，登记项改指 Q4_0；
     * 另有一项盘上始终没有文件，已整条删除（App 与 PC 端配置同步删）。
     * 改 [ModelRegistry] 的登记项时，这里必须同步改 —— 这个测试就是防"列表里
     * 写着某个量化档、目录里根本没有对应文件"的空转。
     */
    private val expectedQuantSuffixes = listOf(
        "Q6_K", "Q4_K_M", "Q4_0"
    )

    @Test
    fun exactlyThreeModels() {
        assertEquals(3, ModelRegistry.models.size)
    }

    @Test
    fun idsAreUnique() {
        val ids = ModelRegistry.models.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun defaultModelIsInList() {
        assertTrue(ModelRegistry.models.any { it.id == ModelRegistry.defaultModel.id })
    }

    @Test
    fun everyNameEndsWithRealGgufQuantization() {
        // 每个模型名必须以一个真实存在的量化档位结尾，
        // 避免再出现"列表里写着 6B 模型、目录里根本没有文件"的空转
        ModelRegistry.models.forEach { m ->
            val suffix = expectedQuantSuffixes.firstOrNull { m.name.endsWith("$it.gguf") }
            assertTrue("模型 ${m.id} 的名字 ${m.name} 与 models/gguf 目录不符", suffix != null)
        }
    }

    @Test
    fun idsMatchServersConfig() {
        // servers.config.json 与代码在同一个仓库，直接读文件校验一致性
        val cfg = java.io.File("../scripts/servers.config.json").readText()
        ModelRegistry.models.forEach { m ->
            assertTrue("servers.config.json 缺少模型 id: ${m.id}", cfg.contains("\"${m.id}\""))
        }
        assertEquals("config 里的模型数必须与代码一致", 3, Regex("\"id\"\\s*:\\s*\"[^\"]+\"").findAll(cfg).count())
    }
}
