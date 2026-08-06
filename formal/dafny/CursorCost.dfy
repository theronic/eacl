module CursorCost {
  // Whole-input pass counts, not wall time. Production exposes matching
  // counters so an accidental extra traversal fails deterministically.
  datatype CodecWork = CodecWork(
    payloadCanonicalPasses: nat,
    authenticationPasses: nat,
    base64Passes: nat,
    payloadBytes: nat,
    authenticationBytes: nat
  )

  function CompactEncodeWork(
    payloadBytes: nat,
    keyIdBytes: nat
  ): CodecWork {
    CodecWork(1, 1, 3, payloadBytes, payloadBytes + keyIdBytes)
  }

  function CompactDecodeWork(
    payloadBytes: nat,
    keyIdBytes: nat
  ): CodecWork {
    CodecWork(1, 1, 3, payloadBytes, payloadBytes + keyIdBytes)
  }

  function TraversedBytes(work: CodecWork): nat {
    work.payloadBytes + work.authenticationBytes
  }

  lemma CompactEncodeHasOneWholePayloadPass(
    payloadBytes: nat,
    keyIdBytes: nat
  )
    ensures CompactEncodeWork(
              payloadBytes,
              keyIdBytes
            ).payloadCanonicalPasses == 1
    ensures CompactEncodeWork(
              payloadBytes,
              keyIdBytes
            ).authenticationPasses == 1
    ensures TraversedBytes(
              CompactEncodeWork(payloadBytes, keyIdBytes)
            ) == 2 * payloadBytes + keyIdBytes
  {
  }

  lemma CompactDecodeHasOneWholePayloadPass(
    payloadBytes: nat,
    keyIdBytes: nat
  )
    ensures CompactDecodeWork(
              payloadBytes,
              keyIdBytes
            ).payloadCanonicalPasses == 1
    ensures CompactDecodeWork(
              payloadBytes,
              keyIdBytes
            ).authenticationPasses == 1
    ensures TraversedBytes(
              CompactDecodeWork(payloadBytes, keyIdBytes)
            ) == 2 * payloadBytes + keyIdBytes
  {
  }

  lemma DoublingPayloadCannotSquareModeledCodecWork(
    payloadBytes: nat,
    keyIdBytes: nat
  )
    ensures TraversedBytes(
              CompactEncodeWork(2 * payloadBytes, keyIdBytes)
            ) <=
            2 * TraversedBytes(
              CompactEncodeWork(payloadBytes, keyIdBytes)
            )
  {
  }
}
