# Spring Boot 实现高效文件上传：前端分片上传与后端处理

## 1. 引言
在现代web应用中，文件上传是一个常见而重要的功能。本文将介绍如何使用Spring Boot实现高效的文件上传，重点关注前端的分片上传技术以及后端使用多线程处理高并发文件上传的策略。

## 2. 前端分片上传

### 2.1 分片上传原理
前端分片上传将大文件分成多个小文件（分片），每个分片独立上传，最后合并成完整文件。这种方式可以有效降低单次上传的复杂度，提高用户体验。

### 2.2 分片上传实现
用户选择文件后，JavaScript会将文件分成1MB的分片，并逐个上传。每个分片还会计算其MD5值，以便后端进行校验，使用已上传的分片比例显示上传进度。

```html
<form id="uploadForm">
    文件: <input type="file" id="file" name="file" required><br>
    <progress id="progressBar" value="0" max="100" style="width: 300px;"></progress>
    <p id="status"></p>
    <button type="submit">提交</button>
</form>

<script>
    const chunkSize = 2 * 1024 * 1024; // 2MB 每片

    document.getElementById("uploadForm").onsubmit = async function(event) {
        event.preventDefault();

        const fileInput = document.getElementById("file");
        const file = fileInput.files[0];
        if (!file) {
            alert("请选择文件！");
            return;
        }

        const totalChunks = Math.ceil(file.size / chunkSize);
        let uploadedChunks = 0;

        for (let chunkNumber = 1; chunkNumber <= totalChunks; chunkNumber++) {
            const start = (chunkNumber - 1) * chunkSize;
            const end = Math.min(start + chunkSize, file.size);
            const chunk = file.slice(start, end);

            const md5 = await calculateMd5(chunk);
            const formData = new FormData();
            formData.append("file", chunk, file.name);
            formData.append("md5", md5);
            formData.append("chunkNumber", chunkNumber);
            formData.append("totalChunks", totalChunks);

            try {
                await uploadChunk(formData);
                uploadedChunks++;
                updateProgress(uploadedChunks, totalChunks);
            } catch (error) {
                console.error("上传分片失败:", error);
                document.getElementById("status").innerText = "上传失败，请重试";
                return;
            }
        }

        document.getElementById("status").innerText = "所有分片上传完成，正在合并文件";
    };

    function calculateMd5(blob) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.readAsBinaryString(blob);
            reader.onloadend = function () {
                const md5 = SparkMD5.hashBinary(reader.result);
                resolve(md5);
            };
            reader.onerror = function (error) {
                reject(error);
            };
        });
    }

    function uploadChunk(formData) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            xhr.open("POST", "/upload", true);
            xhr.onload = function() {
                if (xhr.status === 200) {
                    resolve(xhr.responseText);
                } else {
                    reject(new Error("上传失败，错误代码：" + xhr.status));
                }
            };
            xhr.onerror = function() {
                reject(new Error("网络错误"));
            };
            xhr.send(formData);
        });
    }

    function updateProgress(uploadedChunks, totalChunks) {
        const percentComplete = Math.round((uploadedChunks / totalChunks) * 100);
        document.getElementById("progressBar").value = percentComplete;
        document.getElementById("status").innerText = "上传进度: " + percentComplete + "%";
    }
</script>
```

## 3. 后端分片接收

### 3.1 文件上传处理
后端使用Spring Boot接收每个分片，并进行MD5校验：
```java
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
```
在这个方法中，后端接收每个分片，保存到指定目录，并进行MD5校验以确保数据完整性。

### 3.2 线程池与异步合并
为了提高文件上传的并发处理能力，我们配置了线程池：
```java
@Configuration
public class ThreadPoolConfig {

    @Bean
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("FileUploadThread-");
        executor.initialize();
        return executor;
    }
}
```
线程池详细参数介绍：
- corePoolSize: 线程池的核心线程数，即在没有任务时，线程池中保持的线程数。
- maxPoolSize: 线程池的最大线程数，即线程池中允许的最大线程数。
- queueCapacity: 线程池的队列容量，即线程池中等待执行的任务队列的最大长度。
- threadNamePrefix: 线程池中线程的名称前缀，用于区分不同的线程。

这个配置类创建了一个 `ThreadPoolTaskExecutor` bean，用于管理文件上传的线程池。合并文件的操作在所有分片上传完成后异步执行，减少了主线程的阻塞时间。

### 3.3 文件合并
文件合并是将所有上传的分片合并成一个完整文件的过程：
```java
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
```
在这个方法中，后端将所有分片合并为一个完整的文件，并记录合并成功的日志。

## 4. 总结
通过使用Spring Boot和前端分片上传技术，我们可以实现高效的大文件上传。前端分片上传降低了单次上传的复杂度，提升了用户体验；后端多线程处理和文件合并的优化则大大提高了上传效率，减少了对服务器资源的占用。