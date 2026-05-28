package com.agentloop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import com.agentloop.config.AgentProperties;
import com.agentloop.service.AgentService;
import java.util.List;
import java.util.Scanner;

@org.springframework.boot.autoconfigure.SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration.class
})
@EnableConfigurationProperties(AgentProperties.class)
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    @Bean
    public CommandLineRunner runner(AgentService agentService) {
        return new CommandLineRunner() {
            private static final String INTERACTIVE_FLAG = "--interactive";

            @Override
            public void run(String... args) {
                if (args.length == 0) {
                    runInteractive(agentService);
                    return;
                }
                if (INTERACTIVE_FLAG.equals(args[0])) {
                    runInteractive(agentService);
                } else {
                    String message = String.join(" ", args);
                    System.out.println(agentService.execute(message));
                }
            }
        };
    }

    private void runInteractive(AgentService agentService) {
        System.out.println("Agent Loop — interactive mode. Type 'exit' to quit.\n");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("You: ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty() || "exit".equalsIgnoreCase(input)) break;
            System.out.println("Agent: " + agentService.execute(input) + "\n");
        }
    }
}