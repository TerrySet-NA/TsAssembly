package com.ts.assembly.client.config;

import java.util.Collections;
import java.util.List;

public class RuleConfig {
    public enum TileMode {
        MAIN,
        ORIGINAL_TEXTURE,
        FALLBACK_VANILLA,
        EMPTY
    }

    public String type = "AUTO"; // COMPOSITE, FULL 或 PANE_EDGE
    public List<String> faces = List.of("all");
    public String texture = "";
    public String original_texture = "";
    public String match_sprite = "";
    public List<String> target_blocks = Collections.emptyList();

    public transient TileMode[][] tileModes = new TileMode[6][6];
    public transient boolean isComposite = false;
    public transient boolean isPaneEdge = false;
    public transient boolean hasOriginalTexture = false;
    public transient boolean hasMainTexture = false;
}