"""Test-only loopback gateway for browser tests of the isolated authenticated Paper server."""
import argparse,json,urllib.request,urllib.error
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('fixture',type=Path);args=parser.parse_args();fixture=json.loads(args.fixture.read_text());token=fixture['token']
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):self.forward()
 def do_POST(self):self.forward()
 def forward(self):
  headers={'Authorization':'Bearer '+token}
  if self.headers.get('Content-Type'):headers['Content-Type']=self.headers['Content-Type']
  if self.headers.get('Origin'):headers['Origin']='http://127.0.0.1:18085'
  size=int(self.headers.get('Content-Length','0'))
  if size>2_200_000:self.send_error(413);return
  request=urllib.request.Request('http://127.0.0.1:18085'+self.path,data=self.rfile.read(size) if self.command=='POST' else None,headers=headers,method=self.command)
  try:response=urllib.request.urlopen(request,timeout=20)
  except urllib.error.HTTPError as failure:response=failure
  with response:
   body=response.read();self.send_response(response.status);self.send_header('Content-Type',response.headers.get('Content-Type','text/plain'));self.send_header('Content-Length',len(body));self.end_headers();self.wfile.write(body)
 def log_message(self,*args):pass
print('Test browser gateway: http://127.0.0.1:18086',flush=True)
ThreadingHTTPServer(('127.0.0.1',18086),Handler).serve_forever()
