package com.llmetor.dataagent.dataagent.tools;

import com.llmetor.dataagent.dataagent.file.AgentxFile;
import com.llmetor.dataagent.dataagent.file.EmbeddingService;
import com.llmetor.dataagent.dataagent.file.FileManageService;
import com.llmetor.dataagent.dataagent.file.MultimodalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnalyzeFileTool 三路路由单元测试（对应 M5-测试用例 B/C/D 组路由逻辑）。
 */
class AnalyzeFileToolTest {

    private FileManageService fileManageService;
    private EmbeddingService embeddingService;
    private MultimodalService multimodalService;
    private AnalyzeFileTool tool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fileManageService = mock(FileManageService.class);
        embeddingService = mock(EmbeddingService.class);
        multimodalService = mock(MultimodalService.class);
        ObjectProvider<MultimodalService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(multimodalService);
        tool = new AnalyzeFileTool(fileManageService, embeddingService, provider);
    }

    private AgentxFile file(String type, String status, Integer embed, String extractedText) {
        AgentxFile f = new AgentxFile();
        f.setFileId("f-1");
        f.setFileName("测试文件." + type);
        f.setFileType(type);
        f.setStatus(status);
        f.setEmbed(embed);
        f.setExtractedText(extractedText);
        return f;
    }

    @Test
    void blankFileIdRejected() {
        assertTrue(tool.analyzeFile("", "q").contains("文件 ID 不能为空"));
    }

    @Test
    void nonSuccessStatusReturnsHintWithoutRetrieval() {
        // D01：处理中/失败的文件返回状态提示，不执行检索
        when(fileManageService.getFileInfo("f-1"))
                .thenReturn(file("pdf", "PROCESSING", 0, null));
        String resp = tool.analyzeFile("f-1", "问题");
        assertTrue(resp.contains("PROCESSING"), "应包含状态提示: " + resp);
        verify(embeddingService, never()).ragRetrieve(anyString(), anyString());
    }

    @Test
    void unknownFileIdReturnsFriendlyMessage() {
        when(fileManageService.getFileInfo("nope")).thenThrow(new IllegalArgumentException("文件不存在: nope"));
        assertTrue(tool.analyzeFile("nope", "q").contains("文件不存在"));
    }

    @Test
    void smallTextFileReturnsExtractedTextDirectly() {
        when(fileManageService.getFileInfo("f-1"))
                .thenReturn(file("txt", "SUCCESS", 0, "正文内容"));
        String resp = tool.analyzeFile("f-1", "问题");
        assertTrue(resp.contains("正文内容"));
        verify(embeddingService, never()).ragRetrieve(anyString(), anyString());
    }

    @Test
    void largeFileGoesRagRetrieval() {
        // B 组：embed=1 走 RAG，命中片段分段返回
        when(fileManageService.getFileInfo("f-1"))
                .thenReturn(file("pdf", "SUCCESS", 1, "（截断文本）"));
        when(embeddingService.ragRetrieve("f-1", "第三章讲什么"))
                .thenReturn(List.of("片段A", "片段B"));
        String resp = tool.analyzeFile("f-1", "第三章讲什么");
        assertTrue(resp.contains("[片段 1]"));
        assertTrue(resp.contains("片段A"));
        assertTrue(resp.contains("已向量化"));
    }

    @Test
    void ragEmptyResultDegradesGracefully() {
        // B02：检索失败/空结果降级提示，不抛异常
        when(fileManageService.getFileInfo("f-1"))
                .thenReturn(file("pdf", "SUCCESS", 1, null));
        when(embeddingService.ragRetrieve(anyString(), anyString())).thenReturn(List.of());
        String resp = tool.analyzeFile("f-1", "问题");
        assertTrue(resp.contains("未检索到"));
    }

    @Test
    void imageCacheHitSkipsMultimodal() {
        // C02：已识别过的图片直接命中 extracted_text 缓存，不再调多模态
        when(fileManageService.isImageFile("png")).thenReturn(true);
        when(fileManageService.getFileInfo("f-1"))
                .thenReturn(file("png", "SUCCESS", 0, "一张猫的图片"));
        String resp = tool.analyzeFile("f-1", "图里有什么");
        assertTrue(resp.contains("一张猫的图片"));
        assertTrue(resp.contains("多模态识别"));
        verify(multimodalService, never()).imageToText(any(), anyString());
    }

    @Test
    void imageFirstReadCallsMultimodalAndWritesBack() {
        // C01：首次识别 → 多模态 → 懒缓存回写
        when(fileManageService.isImageFile("jpg")).thenReturn(true);
        when(fileManageService.getFileInfo("f-1"))
                .thenReturn(file("jpg", "SUCCESS", 0, null));
        when(fileManageService.downloadFileBytes("f-1")).thenReturn(new byte[]{1, 2, 3});
        when(multimodalService.imageToText(any(), anyString())).thenReturn("一张海报");
        String resp = tool.analyzeFile("f-1", "图里有什么");
        assertTrue(resp.contains("一张海报"));
        verify(fileManageService, times(1)).saveExtractedText("f-1", "一张海报");
    }

    @Test
    void missingMultimodalServiceDegrades() {
        // 多模态未配置时给友好提示，不抛异常
        when(fileManageService.isImageFile("png")).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<MultimodalService> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        AnalyzeFileTool noMultimodal = new AnalyzeFileTool(fileManageService, embeddingService, emptyProvider);
        when(fileManageService.getFileInfo("f-1")).thenReturn(file("png", "SUCCESS", 0, null));
        assertTrue(noMultimodal.analyzeFile("f-1", "q").contains("图片识别服务未配置"));
    }
}
