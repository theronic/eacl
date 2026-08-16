  // EACL target-cost refinement:
  // Dafny 4.11 represents JavaScript sets as arrays. Membership and immutable
  // single-element union are therefore linear in the current cardinality.
  // Immutable.Map supplies a persistent HAMT while DafnyKey preserves Dafny's
  // structural equality rather than JavaScript object identity.
  class DafnyKey {
    constructor(value) {
      this.value = value;
      this._hash = undefined;
    }
    equals(other) {
      return other instanceof DafnyKey &&
        _dafny.areEqual(this.value, other.value);
    }
    hashCode() {
      if (this._hash === undefined) {
        let hash = 0;
        const text = _dafny.toString(this.value);
        for (let index = 0; index < text.length; index++) {
          hash = ((hash << 5) - hash + text.charCodeAt(index)) | 0;
        }
        this._hash = hash;
      }
      return this._hash;
    }
  }
  $module.Set = class Set {
    constructor(inner) {
      this._inner = inner === undefined ? Immutable.Map() : inner;
    }
    static get Default() {
      return Set.Empty;
    }
    get length() {
      return this._inner.size;
    }
    static get Empty() {
      if (this._empty === undefined) {
        this._empty = new Set();
      }
      return this._empty;
    }
    static fromElements(...elements) {
      const result = new Set();
      for (const element of elements) {
        result.add(element);
      }
      return result;
    }
    static of(...elements) {
      return Set.fromElements(...elements);
    }
    contains(value) {
      return this._inner.has(new DafnyKey(value));
    }
    add(value) {
      const key = new DafnyKey(value);
      const changed = !this._inner.has(key);
      this._inner = this._inner.set(key, value);
      return changed;
    }
    push(...values) {
      for (const value of values) {
        this.add(value);
      }
      return this.length;
    }
    [Symbol.iterator]() {
      return this._inner.valueSeq()[Symbol.iterator]();
    }
    get Elements() {
      return this;
    }
    toString() {
      return "{" + arrayElementsToString(this) + "}";
    }
    equals(other) {
      if (this === other) {
        return true;
      }
      if (!(other instanceof Set) || this.length !== other.length) {
        return false;
      }
      for (const element of this) {
        if (!other.contains(element)) {
          return false;
        }
      }
      return true;
    }
    Union(other) {
      if (this.length === 0) {
        return other;
      }
      if (other.length === 0) {
        return this;
      }
      const larger = this.length >= other.length ? this : other;
      const smaller = larger === this ? other : this;
      const result = new Set(larger._inner);
      for (const element of smaller) {
        result.add(element);
      }
      return result;
    }
    Intersect(other) {
      if (this.length === 0) {
        return this;
      }
      if (other.length === 0) {
        return other;
      }
      const smaller = this.length <= other.length ? this : other;
      const larger = smaller === this ? other : this;
      const result = new Set();
      for (const element of smaller) {
        if (larger.contains(element)) {
          result.add(element);
        }
      }
      return result;
    }
    Difference(other) {
      if (this.length === 0 || other.length === 0) {
        return this;
      }
      let inner = this._inner;
      for (const element of other) {
        inner = inner.remove(new DafnyKey(element));
      }
      return new Set(inner);
    }
    IsDisjointFrom(other) {
      const smaller = this.length <= other.length ? this : other;
      const larger = smaller === this ? other : this;
      for (const element of smaller) {
        if (larger.contains(element)) {
          return false;
        }
      }
      return true;
    }
    IsSubsetOf(other) {
      if (other.length < this.length) {
        return false;
      }
      for (const element of this) {
        if (!other.contains(element)) {
          return false;
        }
      }
      return true;
    }
    IsProperSubsetOf(other) {
      return this.length < other.length && this.IsSubsetOf(other);
    }
    get AllSubsets() {
      return this.AllSubsets_();
    }
    *AllSubsets_() {
      const elements = Array.from(this);
      const count = elements.length;
      const selected = new Array(count).fill(false);
      const current = [];
      while (true) {
        yield Set.fromElements(...current);
        let index = 0;
        for (; index < count && selected[index]; index++) {
          selected[index] = false;
          const removed = current.findIndex(
            value => _dafny.areEqual(value, elements[index]));
          current.splice(removed, 1);
        }
        if (index === count) {
          break;
        }
        selected[index] = true;
        current.push(elements[index]);
      }
    }
  }
