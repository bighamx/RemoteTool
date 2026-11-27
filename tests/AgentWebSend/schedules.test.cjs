const assert = require('node:assert/strict');
const {test} = require('node:test');
const {createHarness} = require('./agent-send.test.cjs');

test('schedule history preserves failed and submitted rows and keeps only active chips', async () => {
    const {state, api, options} = createHarness([]);
    api.get = async () => ({items: [
        {id:'pending',status:'pending',created_at:1},
        {id:'failed',status:'failed',last_error:'connection changed',created_at:2},
        {id:'sent',status:'sent',created_at:3},
        {id:'unknown',status:'delivery_unknown',created_at:4}
    ]});
    await state.loadSchedules();
    assert.equal(state.schedules.length, 4);
    assert.deepEqual(Array.from(options.computed.pendingSchedules.call(state), row => row.id), ['pending']);
    assert.equal(options.computed.failedScheduleCount.call(state), 2);
    assert.equal(state.scheduleStatus(state.schedules.find(row => row.id === 'sent')), '已提交');
    api.get = async () => {throw new Error('offline');};
    await state.loadSchedules();
    assert.equal(state.schedules.length, 4, 'a read failure must preserve the last confirmed records');
    assert.equal(state.schedulesError, 'offline');
    assert.equal(state.schedulesLoading, false);
});

test('late schedule reads cannot replace another session records or newer refreshes', async () => {
    const {state, api} = createHarness([]);
    let finish;
    api.get = () => new Promise(resolve => {finish = resolve;});
    const old = state.loadSchedules();
    state.selectedId = 'session-2';
    api.get = async () => ({items:[{id:'new',created_at:2,status:'pending'}]});
    await state.loadSchedules();
    finish({items:[{id:'old',created_at:1,status:'failed'}]});
    await old;
    assert.equal(state.schedules[0].id, 'new');
    assert.equal(state.schedulesLoading, false);
});
