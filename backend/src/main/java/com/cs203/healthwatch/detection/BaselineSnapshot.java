package com.cs203.healthwatch.detection;

public record BaselineSnapshot(String region, String signalType, double median, double scaledMad) {

    public boolean hasUsableSpread(){
        return Double.isFinite(median) && Double.isFinite(scaledMad) && scaledMad > 0; 
    }
}