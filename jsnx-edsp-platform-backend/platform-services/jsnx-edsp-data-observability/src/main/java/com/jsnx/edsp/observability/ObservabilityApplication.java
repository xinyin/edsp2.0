package com.jsnx.edsp.observability;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"com.jsnx.edsp.service","com.jsnx.edsp.observability"})
public class ObservabilityApplication { public static void main(String[] args){SpringApplication.run(ObservabilityApplication.class,args);} }
