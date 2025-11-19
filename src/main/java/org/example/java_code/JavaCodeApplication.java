package org.example.java_code;

import org.example.java_code.dto.ApiResponse;
import org.example.java_code.dto.GenerationRequest;
import org.example.java_code.service.ApiService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class JavaCodeApplication {

  public static void main(String[] args) {
    SpringApplication.run(org.example.java_code.JavaCodeApplication.class, args);
  }
}

//public class JavaCodeApplication implements CommandLineRunner {
//
//  private final ApiService apiService;
//
//  public JavaCodeApplication(ApiService apiService) {
//    this.apiService = apiService;
//  }
//
//  public static void main(String[] args) {
//    SpringApplication.run(JavaCodeApplication.class, args);
//  }
//
//	@Override
//	public void run(String... args) throws Exception {
//		GenerationRequest request = new GenerationRequest();
//		GenerationRequest.ConfigDTO configDTO = new GenerationRequest.ConfigDTO();
//		GenerationRequest.ConfigDTO.MetadataDTO configMetadata = new GenerationRequest.ConfigDTO.MetadataDTO();
//		configMetadata.setReg("SG");
//		configMetadata.setUserEmail("xinbo.wang");
//		configDTO.setMetadata(configMetadata);
//		request.setConfig(configDTO);
//
//		GenerationRequest.InputDTO inputDTO = new GenerationRequest.InputDTO();
//		inputDTO.setTableGroupId("Order Mart");
//		GenerationRequest.InputDTO.RawDocsDTO rawDoc = new GenerationRequest.InputDTO.RawDocsDTO();
//		rawDoc.setIndexInfo("Order Mart v3 User guide");
//		rawDoc.setTextContent("Order Mart v3 User guide\n\n.......");
//		rawDoc.setTitle("FAQ");
//		inputDTO.setRawDocs(java.util.Collections.singletonList(rawDoc));
//		request.setInput(inputDTO);
//
//		ApiResponse response = apiService.generateIntroduction(request);
//
//		System.out.println("API Response: " + response);
//	}
//}
