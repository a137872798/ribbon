package com.netflix.client.http;

public class UnexpectedHttpResponseException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * http执行的状态码
     */
    private final int statusCode;
    /**
     * 错误消息
     */
    private final String line;
    
    public UnexpectedHttpResponseException(int statusCode, String statusLine) {
        super(statusLine);
        this.statusCode = statusCode;
        this.line = statusLine;
    }
    
    public int getStatusCode() {
        return statusCode;
    }
    
    public String getStatusLine() {
        return this.line;
    }
}
