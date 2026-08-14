package io.github.yourname.cycbercompany;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@ConfigurationPropertiesScan
@EnableScheduling
@SpringBootApplication
public class CycberCompanyApplication {

	/**
	 * Spring Boot 应用入口。
	 *
	 * <p>启动后会自动扫描同包及子包中的 Controller、Service、Repository 和配置类。
	 * {@code @ConfigurationPropertiesScan} 让 {@code AppProperties} 这类配置对象从
	 * {@code application.yml} / 环境变量中绑定；{@code @EnableScheduling} 则开启节点
	 * 心跳检测、持久任务恢复等定时任务。
	 */
	public static void main(String[] args) {
		SpringApplication.run(CycberCompanyApplication.class, args);
	}

}
