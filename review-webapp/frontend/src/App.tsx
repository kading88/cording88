import { useCallback, useEffect, useRef, useState } from 'react';
import { App as AntApp, Button, Drawer, Empty, Form, Input, Select, Spin, Table, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { api, ApiError, refreshCsrf } from './api';
import { LanguageSwitcher, useI18n } from './i18n';
import type { Detail, Label, Lease, Row, Stats, User } from './api';

function Icon({ name, size = 18 }: { name: string; size?: number }) {
  const paths: Record<string, string> = {
    grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
    layers: 'm12 3 10 5-10 5L2 8Zm-10 9 10 5 10-5M2 16l10 5 10-5',
    check: 'm5 12 4 4L19 6', clock: 'M12 8v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
    edit: 'm15 5 4 4M4 20l4-1L20 7a2.8 2.8 0 0 0-4-4L4 15Z',
    refresh: 'M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-2l2 3M18 17a7 7 0 0 1-12 2l-2-3',
    exit: 'M9 4H4v16h5M13 8l4 4-4 4M8 12h13',
    lock: 'M6 10h12v11H6zM8 10V6a4 4 0 0 1 8 0v4M12 14v3',
    arrow: 'M4 12h15m-5-5 5 5-5 5', search: 'm16 16 5 5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name] ?? paths.grid}/></svg>;
}
function LabelTag({ value }: { value: Label | null }) {
  const { t } = useI18n();
  return value ? <span className={`label-tag ${value.toLowerCase()}`}><i/>{t(value === 'NORMAL' ? "normalTraffic" : "attackTraffic")}</span> : <span className="muted">—</span>;
}
function Brand() { return <div className="brand"><div className="brand-symbol"><Icon name="layers" size={25}/></div><div>SCX<span>REVIEW</span></div></div>; }

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let active = true;
    api<User>('/auth/me').then(u => { if (active) setUser(u); }).catch(() => {}).finally(() => { if (active) setLoading(false); });
    const expired = () => setUser(null);
    window.addEventListener('session-expired', expired);
    return () => { active = false; window.removeEventListener('session-expired', expired); };
  }, []);
  if (loading) return <div className="startup"><Brand/><Spin/></div>;
  return user ? <Workspace user={user} onLogout={() => setUser(null)}/> : <Login onLogin={setUser}/>;
}

function Login({ onLogin }: { onLogin: (user: User) => void }) {
  const { t, errorText, language } = useI18n();
  const [form] = Form.useForm();
  useEffect(() => {
    const invalid = form.getFieldsError().filter(field => field.errors.length).map(field => field.name);
    if (invalid.length) void form.validateFields(invalid).catch(() => {});
  }, [language, form]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  async function login(values: { username: string; password: string }) {
    setBusy(true); setError(null);
    try {
      await api('/auth/login', { method: 'POST', body: new URLSearchParams(values) });
      await refreshCsrf(); onLogin(await api<User>('/auth/me'));
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  return <div className="login-page">
    <div className="login-language"><LanguageSwitcher/></div>
    <section className="login-form" aria-label={t("signIn")}>
      {!!error && <div className="notice error" role="alert">{errorText(error)}</div>}
      <Form form={form} layout="vertical" onFinish={login} requiredMark={false} size="large">
        <Form.Item label={t("username")} name="username" rules={[{ required: true, message: t("enterYourUsername") }]}><Input autoComplete="username" placeholder={t("usernamePlaceholder")} maxLength={64}/></Form.Item>
        <Form.Item label={t("password")} name="password" rules={[{ required: true, message: t("enterYourPassword") }]}><Input.Password autoComplete="current-password" placeholder={t("passwordPlaceholder")} maxLength={128}/></Form.Item>
        <Button type="primary" htmlType="submit" loading={busy} block>{t("signIn")}</Button>
      </Form>
    </section>
  </div>;
}

function Workspace({ user, onLogout }: { user: User; onLogout: () => void }) {
  const { message, modal } = AntApp.useApp();
  const { t, number, date: dateText, errorText } = useI18n();
  const [rows, setRows] = useState<Row[]>([]);
  const [total, setTotal] = useState(0);
  const [stats, setStats] = useState<Stats>({total:0,pending:0,reviewed:0,corrected:0});
  const [page, setPage] = useState(1);
  const [label, setLabel] = useState('');
  const [status, setStatus] = useState('');
  const [order, setOrder] = useState('score');
  const [refresh, setRefresh] = useState(0);
  const [busy, setBusy] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [selected, setSelected] = useState<Detail | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [lease, setLease] = useState<Lease | null>(null);
  const leaseRef = useRef<Lease | null>(null);
  const [draftLabel, setDraftLabel] = useState<Label>('NORMAL');
  const [note, setNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [search, setSearch] = useState('');
  const [editNotice, setEditNotice] = useState<'' | 'latest' | 'expired'>('');
  const reload = useCallback(() => setRefresh(x => x + 1), []);
  const remaining = lease ? Math.max(0, Math.ceil((Date.parse(lease.expiresAt) - now) / 1000)) : 0;
  useEffect(() => { leaseRef.current = lease; }, [lease]);
  useEffect(() => { if (!lease) return; const t = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(t); }, [lease]);
  useEffect(() => {
    const controller = new AbortController(); setBusy(true); setLoadError(null);
    const params = new URLSearchParams({page:String(page),size:'20',label,status,order});
    Promise.all([api<{items:Row[];total:number}>(`/records?${params}`, {signal:controller.signal}), api<Stats>('/records/stats', {signal:controller.signal})])
      .then(([list, summary]) => { setRows(list.items); setTotal(list.total); setStats(summary); if (!list.items.length && list.total > 0 && page > 1) setPage(1); })
      .catch(e => { if (!controller.signal.aborted) setLoadError(e); })
      .finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [page,label,status,order,refresh]);

  // Release the lease on page exit when possible; its five-minute expiry handles browser crashes.
  useEffect(() => {
    const leave = () => { const l = leaseRef.current; if (l) void api(`/records/${l.record.id}/unlock`, {method:'POST',body:JSON.stringify({lockToken:l.lockToken}),keepalive:true}).catch(() => {}); };
    window.addEventListener('pagehide', leave); return () => window.removeEventListener('pagehide', leave);
  }, []);

  async function show(id: number) {
    setDetailBusy(true); setSearch(''); setEditNotice('');
    try { setSelected(await api<Detail>(`/records/${id}`)); } catch(e) { message.error(errorText(e)); } finally { setDetailBusy(false); }
  }
  async function beginEdit(preserveDraft = false) {
    if (!selected) return;
    setSaving(true);
    try {
      const next = await api<Lease>(`/records/${selected.id}/lock`, {method:'POST'});
      setSelected(next.record); setLease(next); setNow(Date.now());
      if (!preserveDraft) { setDraftLabel(next.record.reviewedLabel ?? next.record.predictedLabel); setNote(next.record.note); setEditNotice(''); }
      else setEditNotice('latest');
      reload();
    } catch(e) { message.error(errorText(e)); } finally { setSaving(false); }
  }
  async function close() {
    if (lease) { try { await api(`/records/${lease.record.id}/unlock`, {method:'POST',body:JSON.stringify({lockToken:lease.lockToken})}); } catch(e) { message.warning(errorText(e)); } }
    setLease(null); leaseRef.current = null; setSelected(null); setEditNotice(''); reload();
  }
  function requestClose() {
    if (lease && selected && (note !== selected.note || draftLabel !== (selected.reviewedLabel ?? selected.predictedLabel))) {
      modal.confirm({title:t("discardUnsavedChanges"),content:t("closingReleasesTheEditLockOnThisRecord"),okText:t("discardChanges"),cancelText:t("keepEditing"),onOk:close});
    } else void close();
  }
  async function save() {
    if (!lease || !selected) return;
    if (draftLabel !== selected.predictedLabel && !note.trim()) { message.warning(t("enterAReasonWhenChangingTheModelLabel")); return; }
    setSaving(true);
    try {
      setSelected(await api<Detail>(`/records/${selected.id}`, {method:'PATCH',body:JSON.stringify({lockToken:lease.lockToken,reviewedLabel:draftLabel,note})}));
      setLease(null); leaseRef.current = null; setEditNotice(''); reload(); message.success(t("reviewSavedAndEditLockReleased"));
    } catch(e) {
      if (e instanceof ApiError && e.code === 'LOCK_EXPIRED') { setLease({...lease, expiresAt:new Date(0).toISOString()}); setEditNotice('expired'); }
      else message.error(errorText(e));
    } finally { setSaving(false); }
  }
  function remove() {
    if (!selected) return;
    modal.confirm({title:t("deleteRecordId", {id: selected.id}),content:t("theRecordWillBeHiddenFromTheQueueItsOriginalDataStaysInTheDatabase"),okText:t("delete"),okButtonProps:{danger:true},cancelText:t("cancel"),onOk:async () => {
      let owned = lease;
      try {
        if (!owned || remaining <= 0) owned = await api<Lease>(`/records/${selected.id}/lock`, {method:'POST'});
        await api(`/records/${selected.id}`, {method:'DELETE',body:JSON.stringify({lockToken:owned.lockToken})});
        setSelected(null); setLease(null); leaseRef.current = null; reload(); message.success(t("recordDeleted"));
      } catch(e) {
        if (owned) await api(`/records/${selected.id}/unlock`, {method:'POST',body:JSON.stringify({lockToken:owned.lockToken})}).catch(() => {});
        setLease(null); message.error(errorText(e));
      }
    }});
  }
  async function logout() {
    if (lease) { message.warning(t("saveOrCancelYourCurrentEditFirst")); return; }
    try { await api('/auth/logout', {method:'POST'}); await refreshCsrf(); onLogout(); } catch(e) { message.error(errorText(e)); }
  }

  const columns: ColumnsType<Row> = [
    {title:t("trafficRecords"),dataIndex:'id',width:150,render:(id:number,row:Row) => <div><button className="record-link" onClick={() => show(id)}>#{String(id).padStart(5,'0')}</button><div className="cell-sub">{t("sourceRowRow", {row: number(row.sourceRow)})}</div></div>},
    {title:t("modelLabel"),dataIndex:'predictedLabel',width:140,render:(v:Label) => <LabelTag value={v}/>},
    {title:<Tooltip title={t("theSOMScoreRanksRecordsItIsNotACalibratedProbabilityOfCorrectness")}>{t("modelScore")} ⓘ</Tooltip>,dataIndex:'score',width:155,render:(v:number) => <div className="score-cell"><span>{v.toFixed(3)}</span><div className="score-track"><i style={{width:`${v*100}%`}}/></div></div>},
    {title:t("humanLabel"),dataIndex:'reviewedLabel',width:140,render:(v:Label|null) => <LabelTag value={v}/>},
    {title:t("reviewStatus"),key:'status',width:120,render:(_,r) => <span className={`status ${r.reviewedLabel ? 'done' : ''}`}><i/>{r.reviewedLabel ? t("reviewed") : t("pending")}</span>},
    {title:t("lastUpdated"),key:'updated',width:175,render:(_,r) => <div className="last-change">{r.reviewedBy ?? '—'}<div className="cell-sub">{dateText(r.updatedAt)}</div></div>},
    {title:t("actions"),key:'action',width:110,fixed:'right',render:(_,r) => <button className="action-link" onClick={() => show(r.id)}>{r.lockedBy ? <Icon name="lock" size={13}/> : null}{t("view")} <span>↗</span></button>},
  ];
  const cards = [
    {title:t("trafficRecords"),value:stats.total,icon:'layers',foot:t("samplesWithPseudoLabels")},
    {title:t("pending"),value:stats.pending,icon:'clock',foot:t("awaitingHumanReview"),accent:true},
    {title:t("reviewed"),value:stats.reviewed,icon:'check',foot:t("labelsConfirmedOrCorrected")},
    {title:t("corrections"),value:stats.corrected,icon:'edit',foot:t("humanLabelDiffersFromTheModel")},
  ];
  const featureEntries = selected ? Object.entries(selected.features).filter(([key]) => key.toLowerCase().includes(search.toLowerCase())) : [];
  return <div className="app-shell">
    <aside className="sidebar"><Brand/><div className="nav-label">{t("workspace")}</div><button className="nav-item"><Icon name="grid"/> {t("trafficReview")} <span>01</span></button>
      <div className="sidebar-note"><span className="small-label">{t("aboutThisTask")}</span><h3>{t("reviewTheModelSuggestionAndConfirmTheLabel")}</h3><p>{t("startWithLowerScoresAndUseTrafficFeaturesToGuideYourDecision")}</p><div className="mini-grid">{Array.from({length:24},(_,i)=><i key={i} className={i%5===0?'gold':''}/>)}</div></div>
      <div className="sidebar-bottom"><span className="dot"/> SCX · SOM RESEARCH<span>{t("reviewWorkspaceV10")}</span></div>
    </aside>
    <div className="main-area"><header className="topbar"><span>{t("workspace")} <b>/</b> <strong>{t("trafficReview")}</strong></span><div className="account"><LanguageSwitcher/><span className="avatar">{user.username[0].toUpperCase()}</span><div><strong>{user.username}</strong><small>{user.role==='ADMIN'?t("administrator"):t("reviewer")}</small></div><button title={t("signOut")} aria-label={t("signOut")} onClick={logout}><Icon name="exit"/></button></div></header>
      <main><div className="page-heading"><div><div className="eyebrow">{t("reviewWorkspace")}</div><h1>{t("trafficReview")}</h1><p>{t("inspectSOMLabelsAndReviewEachTrafficRecord")}</p></div><Button icon={<Icon name="refresh" size={16}/>} onClick={reload} loading={busy}>{t("refresh")}</Button></div>
        <div className="stats-grid">{cards.map(c => <div className={`stat-card ${c.accent?'accent':''}`} key={c.title}><div className="stat-top">{c.title}<span><Icon name={c.icon}/></span></div><strong>{number(c.value)}</strong><p>{c.foot}</p></div>)}</div>
        <section className="records-panel"><div className="panel-heading"><div><h2>{t("reviewQueue")} <span>{number(total)}</span></h2><p>{t("inspectTheFeaturesAndRecordTheReasonForYourDecision")}</p></div><span className="dataset-chip"><i/> {t("cicTrafficData")}</span></div>
          <div className="filters"><div><span>{t("modelLabel")}</span><Select aria-label={t("filterByModelLabel")} value={label} onChange={v=>{setLabel(v);setPage(1);}} options={[{value:'',label:t("allLabels")},{value:'NORMAL',label:t("normalTraffic")},{value:'ATTACK',label:t("attackTraffic")}]}/></div><div><span>{t("reviewStatus")}</span><Select aria-label={t("filterByReviewStatus")} value={status} onChange={v=>{setStatus(v);setPage(1);}} options={[{value:'',label:t("allStatuses")},{value:'PENDING',label:t("pending")},{value:'REVIEWED',label:t("reviewed")}]}/></div><div className="sort-filter"><span>{t("sort")}</span><Select aria-label={t("sortOrder")} value={order} onChange={v=>{setOrder(v);setPage(1);}} options={[{value:'score',label:t("lowestScoreFirst")},{value:'id',label:t("recordID")}]}/></div></div>
          {!!loadError && <div className="notice error" role="alert">{errorText(loadError)} <button onClick={reload}>{t("retry")}</button></div>}
          <Table<Row> rowKey="id" columns={columns} dataSource={rows} loading={busy||detailBusy} scroll={{x:990}} size="middle" pagination={{current:page,pageSize:20,total,showSizeChanger:false,showTotal:n=>t("countRecords", {count: number(n)}),onChange:setPage}} locale={{emptyText:<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={label||status?t("noRecordsMatchTheseFilters"):t("noRecordsToReviewYet")}/>}}/>
        </section><footer className="workspace-footer"><span>{t("theModelScoreSetsReviewPriorityItIsNotAProbabilityOfCorrectness")}</span><span>SCX REVIEW · {t("humanReviewParticipation")}</span></footer>
      </main>
    </div>
    <Drawer extra={<LanguageSwitcher/>} open={!!selected} onClose={requestClose} size={760} maskClosable={!lease} title={selected ? <div className="drawer-title">{t("trafficRecords")} <span>#{String(selected.id).padStart(5,'0')}</span></div> : ''}
      footer={selected && <div className="drawer-footer">{user.role==='ADMIN'&&<Button danger onClick={remove} disabled={saving}>{t("deleteRecord")}</Button>}<div className="footer-spacer"/><Button onClick={requestClose} disabled={saving}>{lease?t("cancelEdit"):t("close")}</Button>{lease ? <Button type="primary" onClick={save} loading={saving} disabled={remaining<=0}>{t("saveReview")}</Button> : <Button type="primary" icon={<Icon name="edit" size={15}/>} loading={saving} onClick={()=>beginEdit()}>{t("startEditing")}</Button>}</div>}>
      {selected && <><div className="detail-model"><div><span className="small-label">{t("modelSuggestion")}</span><LabelTag value={selected.predictedLabel}/></div><div><span className="small-label">{t("modelScore")}</span><strong>{selected.score.toFixed(3)}</strong></div><div><span className="small-label">{t("neuron")}</span><strong>({selected.somX}, {selected.somY})</strong></div></div>
        <div className="evidence-row"><span>{t("quantizationError")} <b>{selected.quantizationError.toFixed(3)}</b></span><span>{t("neuronSampleCount")} <b>{selected.neuronCount}</b></span><span>{t("labelPurity")} <b>{(selected.clusterPurity*100).toFixed(1)}%</b></span></div>
        <section className="detail-section"><h3>{t("humanReview")}</h3>
          {lease && <div className={`notice ${remaining<=0?'error':'info'}`}><Icon name="lock" size={15}/>{remaining>0?t("editLockAcquiredTimeLeftTime", {time: `${Math.floor(remaining/60)}:${String(remaining%60).padStart(2,'0')}`}):t("theEditLockExpiredYourDraftHasBeenPreserved")}{remaining<=0&&<Button size="small" onClick={()=>beginEdit(true)} loading={saving}>{t("reacquireLock")}</Button>}</div>}
          {editNotice && <div className="notice info">{editNotice === 'expired' ? t("yourEditLockExpiredAcquireItAgainAndCheckTheLatestRecord") : t("latestRecordLoadedHumanLabelLabelYourDraftIsPreservedCheckItBeforeSaving", {label: selected.reviewedLabel ? t(selected.reviewedLabel === 'NORMAL' ? "normalTraffic" : "attackTraffic") : t("notReviewed")})}</div>}
          {lease ? <><label className="field-label">{t("finalLabel")}</label><Select aria-label={t("finalLabel")} value={draftLabel} onChange={setDraftLabel} disabled={remaining<=0||saving} className="full-width" options={[{value:'NORMAL',label:t("normalTrafficNORMAL")},{value:'ATTACK',label:t("attackTrafficATTACK")}]}/><label className="field-label">{t("reviewNotes")} <span>{t("requiredWhenChangingTheLabel")}</span></label><Input.TextArea aria-label={t("reviewNotes")} rows={3} value={note} onChange={e=>setNote(e.target.value)} disabled={saving} maxLength={1000} showCount placeholder={t("recordTheFeaturesOrReasonsSupportingYourDecision")}/></> : <div className="review-summary"><LabelTag value={selected.reviewedLabel}/><p>{selected.note||t("noReviewNotesYet")}</p><small>{selected.reviewedBy?`${selected.reviewedBy} · ${dateText(selected.updatedAt)}`:t("thisRecordIsAwaitingHumanReview")}{selected.lockedBy?` · ${t("nameIsEditing", {name: selected.lockedBy})}`:''}</small></div>}
        </section>
        <section className="detail-section"><div className="feature-heading"><h3>{t("trafficFeatures")} <span>{Object.keys(selected.features).length}</span></h3><Input size="small" prefix={<Icon name="search" size={14}/>} placeholder={t("searchFeatures")} value={search} onChange={e=>setSearch(e.target.value)} allowClear aria-label={t("searchTrafficFeatures")}/></div><div className="feature-grid">{featureEntries.map(([key,value])=><div className="feature" key={key}><span title={key}>{key}</span><b>{value===null?t("missing"):number(value,{maximumFractionDigits:5})}</b></div>)}</div>{!featureEntries.length&&<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("noMatchingFeatures")}/>}</section>
        <div className="detail-provenance">{t("sourceRowRow", {row: number(selected.sourceRow)})} · {t("model")} {selected.modelVersion}</div>
      </>}
    </Drawer>
  </div>;
}
