# org-ietf-xdr

**XDR (IETF RFC 4506) — schema-driven, portable `.cljc`, zero dependencies.**

The codec every ONC RPC protocol is written in. NFS, MOUNT, NLM and the
portmapper all describe themselves in the XDR language and then say nothing
further about bytes, because XDR has already said it.

```clojure
(require '[xdr.core :as xdr])

(def diropargs [:struct [:dir [:opaque* 64]] [:name [:string 255]]])

(xdr/encode diropargs {:dir handle :name "hello.txt"})
(xdr/decode diropargs bytes)   ; => {:value {...} :end 84}
```

A schema is an ordinary EDN value, reviewable next to the RFC it came from
rather than hidden in generated output — the same choice `dev-protobuf`
makes.

## The rules that decide whether a decoder works

- **Everything is a multiple of four bytes.** Opaque and string data carry
  up to three bytes of padding that are not part of the length. A decoder
  that forgets to skip them reads every later field from the wrong offset.
- **There are no optional fields, only optional *data*** (§4.19): a boolean
  followed by the value. NFS uses it in almost every reply.
- **Unions encode their discriminant**, and `void` arms are how a protocol
  says "on failure there is nothing else".
- **`decode` returns `:end`**, not just a value. A stream carries more than
  one message and the caller has to know where this one stopped.

## Bytes

Platform bytes in and out (`byte[]` / `Uint8Array`). Opaque payloads stay as
platform bytes inside decoded values rather than becoming integer vectors —
a 64 KiB NFS READ through a boxed vector is the difference between a
filesystem and a demonstration.

## Test

```bash
clojure -M:test
```

Expectations are byte strings produced independently of the encoder under
test. The stronger oracle is one layer up: the bytes macOS's own NFS client
sends, pinned in `org-ietf-nfs`.

## License

Apache-2.0.
