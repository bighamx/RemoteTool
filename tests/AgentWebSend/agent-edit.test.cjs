const assert = require('node:assert/strict');
const {createHarness} = require('./agent-send.test.cjs');

function editingHarness() {
    const h = createHarness([], 'hermes');
    h.state.editTarget = {id: 10, role: 'user', content: 'original'};
    h.state.messages = [h.state.editTarget];
    h.state.editText = 'edited';
    h.state.editFiles = [];
    h.state.showEditDialog = true;
    h.state.loadHistory = async () => {};
    return h;
}

(async () => {
    let h = editingHarness();
    h.state.externalRunning = true;
    assert.equal(h.state.canEditMessage(h.state.editTarget), false);
    await h.state.submitEdit();
    assert.equal(h.posts.length, 0);
    assert.match(h.state.editError, /其他客户端/);

    h = editingHarness();
    h.api.post = async () => {throw Object.assign(new Error('此消息不支持编辑'), {status:409});};
    await h.state.submitEdit();
    assert.equal(h.state.editError, '此消息不支持编辑');
    assert.equal(h.state.draft, 'edited');
    assert.equal(h.state.showEditDialog, true);

    h = editingHarness();
    h.api.post = async () => {throw new Error('连接中断');};
    await h.state.submitEdit();
    assert.match(h.state.editError, /^结果未确认/);

    h = editingHarness();
    h.api.post = async () => ({rewound:true});
    h.state.loadHistory = async () => {throw Object.assign(new Error('历史读取失败'), {status:409});};
    await h.state.submitEdit();
    assert.match(h.state.editError, /^回退已完成/);
    assert.equal(h.state.error, h.state.editError);

    h = editingHarness();
    let finish;
    h.api.post = () => new Promise(resolve => {finish=resolve;});
    let sent = 0;
    h.state.send = async () => {sent++;};
    const pendingEdit = h.state.submitEdit();
    h.state.selectedId = 'other-session';h.state.epoch++;h.state.draft='other draft';
    finish({rewound:true});await pendingEdit;
    assert.equal(sent,0);
    assert.equal(h.state.draft,'other draft');

    h = editingHarness();
    h.state.externalRunning = true;
    let refreshed = 0;
    h.state.loadHistory = async () => {refreshed++;};
    await h.options.methods.loadStatus.call(h.state,h.state.epoch,{run_id:null},null,{running:false,available:true});
    assert.equal(h.state.canEditMessage(h.state.editTarget),true);
    assert.equal(refreshed,1);
    console.log('Agent web edit: 6 checks passed');
})().catch(error => {console.error(error);process.exitCode=1;});
