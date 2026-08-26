package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrder;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderValidator;
import io.github.lchareln.cex.matching.ValidationResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Encodes the internal M00 semantic history exactly as frozen by the start contract. */
public final class M00Canonicalizer {
  private final PlaceLimitOrderValidator validator;

  public M00Canonicalizer() {
    this(new PlaceLimitOrderValidator());
  }

  M00Canonicalizer(PlaceLimitOrderValidator validator) {
    this.validator = validator;
  }

  public CanonicalHistory canonicalize(List<PlaceLimitOrderInput> inputs) {
    StringBuilder history = new StringBuilder();
    List<ValidationResult> results = new ArrayList<>(inputs.size());
    history.append("M00H1|records=").append(inputs.size()).append('\n');

    for (int index = 0; index < inputs.size(); index++) {
      PlaceLimitOrderInput input = inputs.get(index);
      history.append(inputLine(index, input));

      ValidationResult result = validator.validate(input);
      results.add(result);
      if (result instanceof ValidationResult.Valid) {
        history.append(commandLine(index, validator.normalize(input)));
      }
      history.append(validationLine(index, result));
    }

    byte[] bytes = history.toString().getBytes(StandardCharsets.UTF_8);
    return new CanonicalHistory(bytes, Hashing.semanticDigest(bytes), countLines(bytes), results);
  }

  private static String inputLine(int index, PlaceLimitOrderInput input) {
    return new StringBuilder()
        .append("M00I1|")
        .append(index)
        .append("|type=PLACE_LIMIT_ORDER|instrumentId=")
        .append(framed(input.instrumentId()))
        .append("|orderId=")
        .append(input.orderId())
        .append("|side=")
        .append(framed(input.side()))
        .append("|priceTicks=")
        .append(input.priceTicks())
        .append("|quantityLots=")
        .append(input.quantityLots())
        .append('\n')
        .toString();
  }

  private static String commandLine(int index, PlaceLimitOrder command) {
    return new StringBuilder()
        .append("M00C1|")
        .append(index)
        .append("|type=PLACE_LIMIT_ORDER|instrumentId=")
        .append(framed(command.instrumentId()))
        .append("|orderId=")
        .append(command.orderId().value())
        .append("|side=")
        .append(framed(command.side().name()))
        .append("|priceTicks=")
        .append(command.priceTicks().value())
        .append("|quantityLots=")
        .append(command.quantityLots().value())
        .append('\n')
        .toString();
  }

  private static String validationLine(int index, ValidationResult result) {
    if (result instanceof ValidationResult.Invalid invalid) {
      return "M00V1|"
          + index
          + "|status=INVALID|code="
          + invalid.code().name()
          + "|field="
          + invalid.field()
          + "\n";
    }
    return "M00V1|" + index + "|status=VALID|code=-|field=-\n";
  }

  private static String framed(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
  }

  private static int countLines(byte[] bytes) {
    int lines = 0;
    for (byte value : bytes) {
      if (value == '\n') {
        lines++;
      }
    }
    return lines;
  }
}
