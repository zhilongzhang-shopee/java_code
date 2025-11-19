package org.example.java_code.see;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 简单的数据传输对象
 * 用于SSE推送数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimpleFluxSseData {
    
    /**
     * 数据编号（简单数字）
     */
    private Integer dataNumber;
    
    /**
     * 处理标记
     */
    private String label;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 时间戳
     */
    private Long timestamp;
    
    /**
     * 消息
     */
    private String message;
}

