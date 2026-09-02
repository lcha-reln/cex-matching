package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class QualificationProducerCursorTest {
  @Test
  void overloadDoesNotConsumeASlotButAdmissionDoes() {
    QualificationProducerCursor cursor = new QualificationProducerCursor();

    assertEquals(1, cursor.nextSequence());
    // An OVERLOADED_BEFORE_WAL decision deliberately performs no cursor mutation.
    assertEquals(1, cursor.nextSequence());
    cursor.admitted();
    assertEquals(2, cursor.nextSequence());
  }
}
