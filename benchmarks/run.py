#!/usr/bin/env python3
"""Offline integration and process-kill benchmark. No wallet or chain payments."""
import copy, hashlib, json, os, platform, select, statistics, subprocess, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
os.chdir(ROOT)
JAVA=['java','-cp','build/classes','dev.recover402.Main']
ASSET='0x'+'11'*20; PAYER='0x'+'22'*20; PAYTO='0x'+'33'*20; TX='0x'+'44'*32; BLOCK='0x'+'55'*32
DIGEST=hashlib.sha256(b'GET /report').hexdigest()
TRANSFER='0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef'
def cli(path,*args,ok=True):
    p=subprocess.run(JAVA+[str(path),*args],capture_output=True,text=True,timeout=15)
    if ok and p.returncode: raise AssertionError(p.stderr)
    if not ok:
        assert p.returncode!=0, p.stdout
        return p.stderr
    return json.loads(p.stdout)
def claim(path): return cli(path,'claim','attempt','eip155:8453',ASSET,PAYER,PAYTO,'1000',DIGEST)
class Handler(BaseHTTPRequestHandler):
    mode='success'
    def log_message(self,*_): pass
    def do_POST(self):
        req=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        m=req['method']; mode=self.mode
        if mode=='unavailable':
            self.send_response(503);self.end_headers();return
        if m=='eth_chainId': result='0x1' if mode=='wrong_chain' else '0x2105'
        elif m=='eth_getBlockByNumber':
            finalized=req['params'][0]=='finalized'
            result={'number':'0x9' if mode=='unfinalized' and finalized else '0xa','hash':'0x'+'66'*32 if mode=='reorg' and not finalized else BLOCK}
        else:
            result={'transactionHash':TX,'blockNumber':'0xa','blockHash':BLOCK,'status':'0x0' if mode=='reverted' else '0x1','logs':[{'address':ASSET,'topics':[TRANSFER,'0x'+'0'*24+PAYER[2:],'0x'+'0'*24+PAYTO[2:]],'data':'0x'+format(1000,'064x'),'transactionHash':TX,'blockHash':BLOCK,'removed':False}]}
            log=result['logs'][0]
            if mode=='missing':result=None
            elif mode=='wrong_tx':result['transactionHash']='0x'+'77'*32
            elif mode=='wrong_token':log['address']='0x'+'88'*20
            elif mode=='wrong_payer':log['topics'][1]='0x'+'00'*32
            elif mode=='wrong_recipient':log['topics'][2]='0x'+'00'*32
            elif mode=='wrong_amount':log['data']='0x'+format(999,'064x')
            elif mode=='removed':log['removed']=True
            elif mode=='duplicate_log':result['logs'].append(copy.deepcopy(log))
        payload=json.dumps({'jsonrpc':'2.0','id':1,'result':result}).encode()
        self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(payload)
def run():
    results={'environment':{'os':platform.platform(),'java':subprocess.run(['java','-version'],capture_output=True,text=True).stderr.splitlines()[0],'node':subprocess.check_output(['node','--version'],text=True).strip(),'cpu':platform.processor(),'filesystem':subprocess.check_output(['stat','-f','-c','%T','.'],text=True).strip()},'scope':'Offline fixture RPC and SIGKILL process restart. No live settlement, full SDK or financial safety benchmark.'}
    checks=subprocess.check_output(['java','-cp','build/classes','dev.recover402.Checks'],text=True)
    results['journal_checks_and_latency']=json.loads(checks)
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
    rpc=f'http://127.0.0.1:{server.server_port}'
    rows=[]
    with tempfile.TemporaryDirectory() as tmp:
        tmp=Path(tmp)
        for mode in ['success','missing','unavailable','wrong_chain','unfinalized','reorg','wrong_tx','wrong_token','wrong_payer','wrong_recipient','wrong_amount','removed','duplicate_log','reverted']:
            path=tmp/mode;claim(path);cli(path,'bind','attempt',TX);Handler.mode=mode
            expects_error=mode in ['unavailable','wrong_chain','wrong_tx']
            out=cli(path,'reconcile','attempt',rpc,ok=not expects_error)
            current=cli(path,'inspect','attempt')
            expected='TRANSFER_FINALIZED' if mode=='success' else 'TRANSACTION_REVERTED' if mode=='reverted' else 'UNRESOLVED'
            assert current['settlement']==expected,(mode,current)
            assert current['delivery']=='UNKNOWN'
            assert current['automaticPaymentAllowed'] is False
            rows.append({'scenario':mode,'expected':expected,'actual':current['settlement'],'passed':True})
        # Caller-observed delivery is independent from a transfer match.
        out=cli(tmp/'success','delivered','attempt');assert out['delivery']=='OBSERVED'
        # Loopback service smoke test, including post-kill restart via CLI.
        journal=tmp/'api';api=subprocess.Popen(['java','-cp','build/classes','dev.recover402.Api',str(journal),'0'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            assert select.select([api.stdout],[],[],10)[0], 'API did not start'
            line=api.stdout.readline().strip();port=int(line.rsplit(':',1)[1])
            import urllib.request
            body=json.dumps({'action':'claim','id':'attempt','network':'eip155:8453','asset':ASSET,'payer':PAYER,'payTo':PAYTO,'amount':'1000','requestDigest':DIGEST}).encode()
            with urllib.request.urlopen(urllib.request.Request(f'http://localhost:{port}/attempts',data=body,headers={'Content-Type':'application/json'}),timeout=5) as response:assert json.load(response)['settlement']=='UNRESOLVED'
        finally:api.kill();api.wait(timeout=5)
        assert cli(journal,'inspect','attempt')['settlement']=='UNRESOLVED'
        results['api_smoke']='passed'
        trials=30;comparisons=[]
        for name in ['naive','upstream_default_store','recover402']:
            recovered=0;elapsed=[]
            for i in range(trials):
                journal=tmp/f'crash-{i}'
                if name=='recover402':
                    writer=['java','-cp','build/classes','dev.recover402.CrashWorker',str(journal),'write']
                    reader=['java','-cp','build/classes','dev.recover402.CrashWorker',str(journal),'read']
                else:
                    kind='naive' if name=='naive' else 'upstream'
                    writer=['node','benchmarks/store-worker.mjs',kind,'write'];reader=['node','benchmarks/store-worker.mjs',kind,'read']
                p=subprocess.Popen(writer,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                try:
                    assert select.select([p.stdout],[],[],10)[0], 'writer acknowledgement timeout'
                    assert p.stdout.readline().strip()=='READY',p.stderr.read()
                finally:p.kill();p.wait(timeout=5)
                start=time.perf_counter_ns();out=json.loads(subprocess.check_output(reader,text=True,timeout=10));elapsed.append((time.perf_counter_ns()-start)/1e6)
                recovered+=out['recovered']
            comparisons.append({'implementation':name,'trials':trials,'transaction_bindings_recovered':recovered,'restart_and_query_p50_ms':statistics.median(elapsed),'restart_and_query_p95_ms':sorted(elapsed)[int(.95*(trials-1))]})
    server.shutdown();server.server_close()
    results['rpc_scenarios']=rows;results['crash_retention']=comparisons
    results['upstream']=json.loads((ROOT/'benchmarks/upstream/provenance.json').read_text())
    results['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'src').rglob('*.java'))}
    (ROOT/'benchmarks/results.json').write_text(json.dumps(results,indent=2)+'\n')
    print(json.dumps(results,indent=2))
if __name__=='__main__':run()
