package com.agentloop.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Returns the current UTC date and time in ISO-8601 format.
 */
@Component
@Slf4j
public class CurrentDateTool {

    @Tool(name = "get_date", description = "Get the current UTC date and time in ISO-8601 format")
    public String apply() {
        log.info("CurrentDateTool invoked");
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}