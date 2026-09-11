package com.itdou.ragknowledgesystem;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RagKnowledgeSystemApplicationTests {

    @Test
    void applicationNameIsStable() {
        assertEquals("RagKnowledgeSystem", RagKnowledgeSystemApplication.class.getSimpleName().replace("Application", ""));
    }

}
