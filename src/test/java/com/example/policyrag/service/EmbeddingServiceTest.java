package com.example.policyrag.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EmbeddingServiceTest {
    private final EmbeddingService embeddings = new EmbeddingService();

    @Test
    void producesRepeatableNormalizedSemanticVectorsLocally() {
        float[] vector = embeddings.embedDocument("Employees can claim approved dinner expenses.");
        assertThat(vector).hasSize(384).containsExactly(embeddings.embedDocument("Employees can claim approved dinner expenses."));
        assertThat(dot(vector, vector)).isCloseTo(1, within(.0001));
        for (float value : vector) assertThat(Float.isFinite(value)).isTrue();
    }

    @Test
    void matchesAParaphrasedMealQuestionToItsRuleInsteadOfUnrelatedHrPolicies() {
        float[] question = embeddings.embedQuery("Will my employer pay me back for dinner when I stay late at work?");
        float[] meals = embeddings.embedDocument("An employee working from the office may claim the actual cost of food items purchased after 8 PM, up to Rs. 1,000 per qualifying day.");
        float[] leave = embeddings.embedDocument("Employees receive twenty days of annual leave and must obtain approval for planned absences.");
        float[] insurance = embeddings.embedDocument("Corporate medical insurance covers eligible hospitalization, subject to the insurer's plan terms.");
        assertThat(dot(question, meals)).isGreaterThan(dot(question, leave) + .03)
                .isGreaterThan(dot(question, insurance) + .03);
    }

    @Test
    void rejectsEmptyInputInsteadOfIndexingAnUnusableVector() {
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.embedDocument("  "));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.embedQuery(null));
    }

    private double dot(float[] left, float[] right) {
        double result = 0;
        for (int index = 0; index < left.length; index++) result += left[index] * right[index];
        return result;
    }
}
