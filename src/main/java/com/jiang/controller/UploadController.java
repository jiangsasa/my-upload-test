package com.jiang.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;

@RestController
public class UploadController {

    private static final Logger logger = LoggerFactory.getLogger(UploadController.class);

    @Value("${upload.directory}")
    private String uploadDirectory;

    @Autowired
    private ThreadPoolTaskExecutor taskExecutor;

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam("md5") String md5,
                         @RequestParam("chunkNumber") int chunkNumber,
                         @RequestParam("totalChunks") int totalChunks) throws IOException {
        if (file.isEmpty()) {
            logger.warn("上传的文件为空");
            return "文件为空";
        }

        // 获取文件名
        String originalFilename = file.getOriginalFilename();
        logger.info("开始上传文件: {}, 分片: {}/{}", originalFilename, chunkNumber, totalChunks);

        // 使用配置的上传目录
        File uploadPath = new File(uploadDirectory);
        if (!uploadPath.exists()) {
            uploadPath.mkdirs();
        }

        // 分片文件名
        String chunkFileName = originalFilename + ".part" + chunkNumber;
        File chunkFile = new File(uploadPath, chunkFileName);

        // 保存分片文件
        file.transferTo(chunkFile);
        logger.info("分片文件保存成功: {}", chunkFileName);

        // MD5校验
        String calculatedMd5 = calculateMd5(chunkFile);
        if (!calculatedMd5.equals(md5)) {
            chunkFile.delete();
            logger.error("MD5校验失败，分片: {}", chunkNumber);
            return "MD5校验失败，请重新上传分片";
        }
        logger.info("MD5校验成功，分片: {}", chunkNumber);

        // 检查是否所有分片都已上传
        if (chunkNumber == totalChunks) {
            logger.info("所有分片上传完成，开始合并文件: {}", originalFilename);
            CompletableFuture.runAsync(() -> {
                try {
                    mergeChunks(originalFilename, totalChunks, uploadDirectory);
                } catch (IOException e) {
                    logger.error("文件合并失败: {}", originalFilename, e);
                }
            }, taskExecutor);
            return "所有分片上传完成，正在合并文件";
        }

        return "分片" + chunkNumber + "上传成功";
    }

    // 计算文件的MD5值
    private String calculateMd5(File file) throws IOException {
        String md5 = DigestUtils.md5DigestAsHex(Files.newInputStream(file.toPath()));
        logger.debug("计算MD5值: {}", md5);
        return md5;
    }

    // 合并分片文件
    private void mergeChunks(String fileName, int totalChunks, String uploadDir) throws IOException {
        logger.info("开始合并文件: {}", fileName);
        File mergedFile = new File(uploadDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(mergedFile)) {
            for (int i = 1; i <= totalChunks; i++) {
                File chunkFile = new File(uploadDir, fileName + ".part" + i);
                Files.copy(chunkFile.toPath(), fos);
                chunkFile.delete();
                logger.info("合并并删除分片: {}.part{}", fileName, i);
            }
        }
        logger.info("文件合并完成：{}", fileName);
    }
}
