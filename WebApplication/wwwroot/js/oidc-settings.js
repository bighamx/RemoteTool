(() => {
    const root = document.getElementById('identity-settings-app');
    if (!root || !window.Vue || !window.RemoteToolApi) return;
    const api = window.RemoteToolApi;
    const empty = () => ({ id: null, name: '', issuer: '', clientId: '', clientSecret: '', enabled: true, useUserInfo: false,
        discovery: true, discoveryUrl: '', authorizationUrl: '', tokenUrl: '', certificateUrl: '', userInfoUrl: '' });
    const app = Vue.createApp({
        data: () => ({ activeTab: 'oidc', providers: [], form: null, emailsText: '', scopesText: 'openid profile email', access: { enabled: false, team: '', audience: '' }, accessEmails: '', error: '', notice: '', saving: false }),
        computed: { callbackUrl() { return location.origin + '/access/callback'; } },
        methods: {
            selectTab(tab) { if (this.saving || !['oidc', 'access'].includes(tab)) return; this.activeTab = tab; this.error = ''; this.notice = ''; },
            onTabKeydown(event) {
                if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
                event.preventDefault();
                const tab = event.key === 'Home' ? 'oidc' : event.key === 'End' ? 'access' : this.activeTab === 'oidc' ? 'access' : 'oidc';
                this.selectTab(tab);
                this.$nextTick(() => this.$refs[tab === 'oidc' ? 'oidcTab' : 'accessTab']?.focus());
            },
            async refresh() {
                try {
                    await Promise.all([this.refreshProviders(), this.refreshAccess()]);
                } catch (error) { this.error = error.message; }
            },
            async refreshProviders() { this.providers = await api.get('/api/auth/oidc/settings'); },
            async refreshAccess() { const access = await api.get('/api/auth/oidc/access-settings'); this.access = access; this.accessEmails = (access.allowedEmails || []).join('\n'); },
            newProvider() { this.form = empty(); this.emailsText = ''; this.scopesText = 'openid profile email'; this.error = ''; this.notice = ''; },
            editProvider(item) { this.form = { id: item.id, name: item.name, issuer: item.issuer, clientId: item.clientId, clientSecret: '', enabled: item.enabled,
                useUserInfo: item.useUserInfo, discovery: item.discovery, discoveryUrl: item.discoveryUrl || '',
                authorizationUrl: item.authorizationUrl || '', tokenUrl: item.tokenUrl || '', certificateUrl: item.certificateUrl || '', userInfoUrl: item.userInfoUrl || '' };
                this.emailsText = (item.allowedEmails || []).join('\n'); this.scopesText = (item.scopes || ['openid','profile','email']).join(' '); this.error = ''; this.notice = ''; },
            emails(value) { return value.split(/[\r\n,;]+/).map(x => x.trim()).filter(Boolean); },
            async saveProvider() {
                if (!this.form || this.saving) return;
                this.saving = true; this.error = ''; this.notice = '';
                try {
                    const payload = { ...this.form, allowedEmails: this.emails(this.emailsText), scopes: this.scopesText.split(/\s+/).filter(Boolean) };
                    const result = await api.post('/api/auth/oidc/settings', payload);
                    await this.refreshProviders();
                    this.editProvider(this.providers.find(x => x.id === result.id));
                    this.notice = 'OIDC 提供方已保存，立即生效。请在提供方登记上方显示的精确回调地址。';
                } catch (error) { this.error = error.message; } finally { this.saving = false; }
            },
            async deleteProvider() {
                if (!this.form?.id || this.saving || !confirm('删除此 OIDC 提供方？该提供方签发的登录会话将在下次请求时失效。')) return;
                this.saving = true; this.error = ''; this.notice = '';
                try { await api.post('/api/auth/oidc/settings/' + api.id(this.form.id) + '/delete'); this.form = null; await this.refreshProviders(); this.notice = '已删除提供方'; }
                catch (error) { this.error = error.message; } finally { this.saving = false; }
            },
            async saveAccess() {
                if (this.saving) return;
                this.saving = true; this.error = ''; this.notice = '';
                try {
                    this.access = await api.request('/api/auth/oidc/access-settings', { method: 'PUT', body: JSON.stringify({ ...this.access, allowedEmails: this.emails(this.accessEmails) }) });
                    this.accessEmails = (this.access.allowedEmails || []).join('\n');
                    this.notice = 'Cloudflare Access 配置已保存，立即生效。';
                } catch (error) { this.error = error.message; } finally { this.saving = false; }
            }
        },
        mounted() { this.refresh(); }
    });
    app.use(ViewUIPlus);
    app.mount(root);
})();
