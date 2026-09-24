package com.sl.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import com.sl.service.FileStorageService;

/**
 * SpringBoot中CommandLineRunner的作用
 平常开发中有可能需要实现在项目启动后执行的功能，
SpringBoot提供的一种简单的实现方案就是添加一个model并实现CommandLineRunner接口，实现功能的代码放在实现的run方法中
*/
@Service
public class FileUploadConfiguration implements CommandLineRunner {

    @Autowired
    FileStorageService fileStorageService;

    @Override
    public void run(String... args) throws Exception {
        // 只保证目录存在。原实现在每次启动时先 clear() 再 init()，
        // 会把用户之前上传的秘钥等文件全部删掉，属于数据丢失。
        fileStorageService.init();
    }
}