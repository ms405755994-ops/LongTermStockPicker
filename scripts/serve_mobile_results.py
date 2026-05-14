#!/usr/bin/env python3
import argparse
import functools
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        print("%s - - %s" % (self.client_address[0], format % args))

    def copyfile(self, source, outputfile):
        try:
            super().copyfile(source, outputfile)
        except (BrokenPipeError, ConnectionResetError):
            pass


def main():
    parser = argparse.ArgumentParser(description="Serve LongTermStockPicker mobile result files.")
    parser.add_argument("--dir", default="outputs/mobile", help="directory to serve")
    parser.add_argument("--host", default="0.0.0.0", help="listen address")
    parser.add_argument("--port", type=int, default=8765, help="listen port")
    args = parser.parse_args()

    root = Path(args.dir)
    root.mkdir(parents=True, exist_ok=True)
    handler = functools.partial(QuietHandler, directory=str(root))
    server = ThreadingHTTPServer((args.host, args.port), handler)
    print(f"Serving mobile results at http://{args.host}:{args.port}/", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
