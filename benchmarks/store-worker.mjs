import { InMemoryPendingSettlementStore } from './upstream/pendingSettlementStore.ts';
const store = process.argv[2] === 'naive' ? new Map() : new InMemoryPendingSettlementStore();
if (process.argv[3] === 'write') {
  await store.set('attempt', '0x' + '44'.repeat(32));
  console.log('READY');
  setInterval(() => {}, 10000);
} else {
  console.log(JSON.stringify({ recovered: (await store.get('attempt')) !== undefined }));
}
