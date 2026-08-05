  // EACL target-cost refinement. See dafny-set.js in this directory.
  $module.Map = class Map {
    constructor(inner) {
      this._inner = inner === undefined ? Immutable.Map() : inner;
    }
    static get Default() {
      return Map.Empty;
    }
    static get Empty() {
      if (this._empty === undefined) {
        this._empty = new Map();
      }
      return this._empty;
    }
    static of(...entries) {
      const result = new Map();
      for (const [key, value] of entries) {
        result.updateUnsafe(key, value);
      }
      return result;
    }
    get length() {
      return this._inner.size;
    }
    slice() {
      return new Map(this._inner);
    }
    [Symbol.iterator]() {
      return this.entries();
    }
    *entries() {
      for (const pair of this._inner.valueSeq()) {
        yield pair;
      }
    }
    findIndex(key) {
      let index = 0;
      for (const [candidate] of this) {
        if (_dafny.areEqual(candidate, key)) {
          return index;
        }
        index++;
      }
      return this.length;
    }
    get(key) {
      const pair = this._inner.get(new DafnyKey(key));
      return pair === undefined ? undefined : pair[1];
    }
    contains(key) {
      return this._inner.has(new DafnyKey(key));
    }
    update(key, value) {
      return new Map(
        this._inner.set(new DafnyKey(key), [key, value]));
    }
    updateUnsafe(key, value) {
      this._inner =
        this._inner.set(new DafnyKey(key), [key, value]);
      return this;
    }
    equals(other) {
      if (this === other) {
        return true;
      }
      if (!(other instanceof Map) || this.length !== other.length) {
        return false;
      }
      for (const [key, value] of this) {
        if (!other.contains(key) ||
            !_dafny.areEqual(value, other.get(key))) {
          return false;
        }
      }
      return true;
    }
    toString() {
      return "map[" +
        Array.from(
          this,
          ([key, value]) =>
            _dafny.toString(key) + " := " + _dafny.toString(value)
        ).join(", ") +
        "]";
    }
    get Keys() {
      const result = new _dafny.Set();
      for (const [key] of this) {
        result.add(key);
      }
      return result;
    }
    get Values() {
      const result = new _dafny.Set();
      for (const [, value] of this) {
        result.add(value);
      }
      return result;
    }
    get Items() {
      const result = new _dafny.Set();
      for (const [key, value] of this) {
        result.add(_dafny.Tuple.of(key, value));
      }
      return result;
    }
    Merge(other) {
      let inner = other._inner;
      for (const [key, value] of this) {
        const wrapped = new DafnyKey(key);
        if (!inner.has(wrapped)) {
          inner = inner.set(wrapped, [key, value]);
        }
      }
      return new Map(inner);
    }
    Subtract(keys) {
      if (this.length === 0 || keys.length === 0) {
        return this;
      }
      let inner = this._inner;
      for (const key of keys) {
        inner = inner.remove(new DafnyKey(key));
      }
      return new Map(inner);
    }
  }
