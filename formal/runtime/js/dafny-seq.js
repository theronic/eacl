  // EACL target-cost refinement:
  // Dafny 4.11 represents JavaScript sequences as eager arrays, so suffix
  // slicing and immutable append copy the whole represented prefix. Indexed
  // traversal consumes one backend value at a time and appends one rendered
  // value at a time; eager arrays turn both linear processes quadratic.
  //
  // This implementation mirrors the lazy-concatenation strategy in Dafny's
  // Java runtime. It remains an Array proxy so ClojureScript's array-seq and
  // ordinary JavaScript indexing observe the same interface. Concat and slice
  // are persistent O(1) views. The first operation requiring a flat value
  // materializes exactly once and caches the resulting array.
  const seqInternal = Symbol("eacl.dafny.seq.internal");
  const seqIndex = /^(0|[1-9][0-9]*)$/;
  const seqProxy = {
    get(target, property, receiver) {
      if (property === "length") {
        return target._logicalLength;
      }
      if (typeof property === "string" && seqIndex.test(property)) {
        return target._select(Number(property));
      }
      return Reflect.get(target, property, receiver);
    },
    set(target, property, value, receiver) {
      if (property === "length") {
        target._logicalLength = Number(value);
        return true;
      }
      if (typeof property === "string" && seqIndex.test(property)) {
        const index = Number(property);
        const values = target._forceValues();
        values[index] = value;
        if (target._logicalLength <= index) {
          target._logicalLength = index + 1;
        }
        return true;
      }
      return Reflect.set(target, property, value, receiver);
    },
  };
  const seqInteger = value => {
    if (value === undefined) {
      return undefined;
    }
    if (BigNumber.isBigNumber(value)) {
      return value.toNumber();
    }
    return Number(value);
  };
  $module.Seq = class Seq extends Array {
    constructor(...arguments_) {
      const internal = arguments_[0] === seqInternal;
      const node = internal ? arguments_[1] : null;
      const values = internal ? null : arguments_;
      const length = internal ? node.length : values.length;
      // Keep the physical Array empty and expose the Dafny sequence length
      // through the proxy. V8 otherwise allocates and repeatedly scans sparse
      // backing stores for each persistent suffix view.
      super();
      this._logicalLength = length;
      if (internal) {
        this._kind = node.kind;
        this._values = node.values;
        this._left = node.left;
        this._right = node.right;
        this._base = node.base;
        this._start = node.start;
      } else {
        this._kind = "leaf";
        this._values = values;
        this._left = null;
        this._right = null;
        this._base = null;
        this._start = 0;
      }
      return new Proxy(this, seqProxy);
    }
    static _node(node) {
      return new Seq(seqInternal, node);
    }
    static get Default() {
      return Seq.of();
    }
    static of(...values) {
      return new Seq(...values);
    }
    static from(values, mapper) {
      return new Seq(...Array.from(values, mapper));
    }
    static Create(count, initialize) {
      return Seq.from(
        {length: count},
        (_, index) => initialize(new BigNumber(index)),
      );
    }
    static UnicodeFromString(value) {
      return new Seq(
        ...[...value].map(
          character =>
            new _dafny.CodePoint(character.codePointAt(0))),
      );
    }
    _forceValues() {
      if (this._kind === "leaf") {
        return this._values;
      }
      const flattened = [];
      const stack = [this];
      while (stack.length > 0) {
        const current = stack.pop();
        if (current._kind === "leaf") {
          flattened.push(...current._values);
        } else if (current._kind === "concat") {
          stack.push(current._right);
          stack.push(current._left);
        } else if (current._kind === "slice") {
          for (let index = 0;
               index < current._logicalLength;
               index++) {
            flattened.push(
              current._base._select(current._start + index));
          }
        } else {
          throw new Error("unknown persistent Dafny sequence node");
        }
      }
      this._kind = "leaf";
      this._values = flattened;
      this._left = null;
      this._right = null;
      this._base = null;
      this._start = 0;
      return flattened;
    }
    _select(index) {
      if (!Number.isInteger(index) ||
          index < 0 ||
          index >= this._logicalLength) {
        return undefined;
      }
      if (this._kind === "leaf") {
        return this._values[index];
      }
      if (this._kind === "slice") {
        return this._base._select(this._start + index);
      }
      return this._forceValues()[index];
    }
    [Symbol.iterator]() {
      return this._forceValues()[Symbol.iterator]();
    }
    push(...values) {
      const materialized = this._forceValues();
      materialized.push(...values);
      this._logicalLength = materialized.length;
      return materialized.length;
    }
    slice(start, end) {
      const length = this._logicalLength;
      let from = seqInteger(start);
      let until = seqInteger(end);
      from = from === undefined ? 0 : from;
      until = until === undefined ? length : until;
      from = from < 0 ? Math.max(length + from, 0) : Math.min(from, length);
      until =
        until < 0 ? Math.max(length + until, 0) : Math.min(until, length);
      const selectedLength = Math.max(until - from, 0);
      if (selectedLength === 0) {
        return Seq.of();
      }
      if (from === 0 && selectedLength === length) {
        return this;
      }
      if (this._kind === "slice") {
        return Seq._node({
          kind: "slice",
          base: this._base,
          start: this._start + from,
          length: selectedLength,
          values: null,
          left: null,
          right: null,
        });
      }
      return Seq._node({
        kind: "slice",
        base: this,
        start: from,
        length: selectedLength,
        values: null,
        left: null,
        right: null,
      });
    }
    map(mapper) {
      return this._forceValues().map(mapper);
    }
    join(separator) {
      return this._forceValues().join(separator);
    }
    toString() {
      return "[" + arrayElementsToString(this) + "]";
    }
    toVerbatimString(asLiteral) {
      const value =
        this.map(character =>
          asLiteral
            ? _dafny.escapeCharacter(character)
            : String.fromCodePoint(character.value)).join("");
      return asLiteral ? '"' + value + '"' : value;
    }
    equals(other) {
      if (this === other) {
        return true;
      }
      if (other === null || other === undefined ||
          this.length !== other.length) {
        return false;
      }
      const left = this._forceValues();
      for (let index = 0; index < left.length; index++) {
        if (!_dafny.areEqual(left[index], other[index])) {
          return false;
        }
      }
      return true;
    }
    get Elements() {
      return this;
    }
    get UniqueElements() {
      return _dafny.Set.fromElements(...this);
    }
    static update(sequence, index, value) {
      if (typeof sequence === "string") {
        const numericIndex = seqInteger(index);
        return sequence.slice(0, numericIndex) +
          value +
          sequence.slice(numericIndex + 1);
      }
      const values = Array.from(sequence);
      values[seqInteger(index)] = value;
      return new Seq(...values);
    }
    static contains(sequence, value) {
      if (typeof sequence === "string") {
        return sequence.includes(value);
      }
      for (const candidate of sequence) {
        if (_dafny.areEqual(candidate, value)) {
          return true;
        }
      }
      return false;
    }
    static Concat(left, right) {
      if (typeof left === "string" || typeof right === "string") {
        const leftString =
          typeof left === "string" ? left : left.join("");
        const rightString =
          typeof right === "string" ? right : right.join("");
        return leftString + rightString;
      }
      if (left.length === 0) {
        return right;
      }
      if (right.length === 0) {
        return left;
      }
      return Seq._node({
        kind: "concat",
        left,
        right,
        length: left.length + right.length,
        values: null,
        base: null,
        start: 0,
      });
    }
    static JoinIfPossible(value) {
      try {
        return value.join("");
      } catch (_error) {
        return value;
      }
    }
    static IsPrefixOf(left, right) {
      if (right.length < left.length) {
        return false;
      }
      for (let index = 0; index < left.length; index++) {
        if (!_dafny.areEqual(left[index], right[index])) {
          return false;
        }
      }
      return true;
    }
    static IsProperPrefixOf(left, right) {
      return left.length < right.length && Seq.IsPrefixOf(left, right);
    }
  }
