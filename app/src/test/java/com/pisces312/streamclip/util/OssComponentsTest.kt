package com.pisces312.streamclip.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关于页「开源许可」数据源的守门测试。
 *
 * 目标不是验证 UI，而是**防止清单悄悄失真**：漏掉许可证全文、版本留空、
 * 组件重复登记，这些都不会让构建失败，却会让 GPL 合规声明说假话。
 */
class OssComponentsTest {

    private val components: List<OssComponent> = OssComponents.all()

    @Test
    fun every_component_declares_the_full_set_of_fields() {
        components.forEach { component ->
            assertTrue("${component.name}: 名称为空", component.name.isNotBlank())
            assertTrue("${component.name}: 版本为空", component.version.isNotBlank())
            assertTrue("${component.name}: 许可证为空", component.license.isNotBlank())
            assertNotEquals("${component.name}: 没有绑定许可证全文", 0, component.licenseRes)
            assertTrue(
                "${component.name}: 源码地址不是 https",
                component.url.startsWith("https://")
            )
        }
    }

    @Test
    fun component_names_are_unique() {
        val names = components.map { it.name }
        assertEquals("存在重复登记的组件", names.size, names.toSet().size)
    }

    @Test
    fun label_renders_name_version_and_license() {
        val ffmpeg = components.first { it.name == "FFmpeg" }
        assertEquals("FFmpeg · ${ffmpeg.version} — GPL-3.0", ffmpeg.label)
    }

    @Test
    fun native_libraries_are_listed_before_java_dependencies() {
        val names = components.map { it.name }
        val ffmpeg = names.indexOf("FFmpeg")
        val lame = names.indexOf("LAME")
        val androidX = names.indexOf("AndroidX Core")
        assertTrue("原生库应排在前面", ffmpeg == 0 && lame > ffmpeg)
        assertTrue("Java 依赖应排在原生库之后", androidX > lame)
    }

    /** 当前 AAR 实际编入的静态库，少一个都说明清单过期。 */
    @Test
    fun native_section_covers_every_statically_linked_library() {
        val names = components.map { it.name }
        listOf("FFmpeg", "FFmpegKitNext", "x264", "x265", "LAME", "libiconv", "cpu_features").forEach {
            assertTrue("原生库清单缺少 $it", it in names)
        }
    }

    /** FFmpeg 版本取不到 native 时仍要给出可用版本号，不能是空串。 */
    @Test
    fun ffmpeg_version_never_blank() {
        val ffmpeg = components.first { it.name == "FFmpeg" }
        assertTrue("FFmpeg 版本号为空", ffmpeg.version.isNotBlank())
        assertTrue(
            "FFmpeg 版本号不是 x.y 形式: ${ffmpeg.version}",
            Regex("""\d+\.\d+""").containsMatchIn(ffmpeg.version)
        )
    }
}
