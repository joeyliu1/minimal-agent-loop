package com.agentloop.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/**
 * Reads the full content of a local text file.
 */
@Component
@Slf4j
public class FileReadTool {

    @Tool(name = "read_file", description = "Read the full content of a local text file")
    public String apply(@ToolParam(description = "absolute file path") String path) {
        try {
            log.info("FileReadTool invoked: {}", path);
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[error] " + e.getMessage();
        }
    }
}