#!/usr/bin/env python3
"""Copies a `docker save` archive from standard input to standard output, leaving out the layers named
on the command line (diff IDs, sha256:<hex>). Used by push-images.sh.

docker save names each layer blob after its digest, which for an image in Docker's classic (graphdriver)
store is its diff ID, the uncompressed digest. docker load on such a store looks a layer up by its chain ID
before it opens the layer's file, so a layer the other side already has, with all the layers below it,
never needs to be in the archive. The archive is streamed: nothing is kept in memory or on disk.
"""
import sys
import tarfile


def main():
    drop = {"blobs/sha256/" + d.removeprefix("sha256:") for d in sys.argv[1:]}
    dropped = 0
    with tarfile.open(fileobj=sys.stdin.buffer, mode="r|") as src, \
            tarfile.open(fileobj=sys.stdout.buffer, mode="w|", format=tarfile.PAX_FORMAT) as dst:
        for member in src:
            if member.name in drop:
                dropped += member.size
                continue
            dst.addfile(member, src.extractfile(member) if member.isfile() else None)
    print(f"left out {dropped // (1024 * 1024)} MB of layers the remote already has", file=sys.stderr)


if __name__ == "__main__":
    main()
