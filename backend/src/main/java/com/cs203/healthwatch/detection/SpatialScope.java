package com.cs203.healthwatch.detection;

/** How widely a flagged spike shows up across regions at the same time (CG-170). Computed in code, never by the AI. */
public enum SpatialScope { LOCAL, MULTI_REGION, ISLAND_WIDE, UNKNOWN }
