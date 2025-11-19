package org.example.java_code.service;

import org.example.java_code.dto.ApiResponse;
import org.example.java_code.dto.GenerationRequest;

public interface ApiService {
    ApiResponse generateIntroduction(GenerationRequest request);
}

