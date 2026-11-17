package com.example.policyrag.service;

import java.util.List;

public interface SemanticChunker {
    List<String> chunk(String text);
}
