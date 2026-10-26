(() => {
  const roles = {
    counselor: { label: 'COUNSELOR VIEW', tasks: ['把今天要做的事排在前面。', '以截止时间、责任人和当前状态组织任务，减少在多个页面之间来回查找。', '4', '项待处理任务'] },
    student: { label: 'STUDENT VIEW', tasks: ['每一次提交，都能看到下一步。', '学生只看到与本人相关的请求、补正要求和确认结果，减少无关信息干扰。', '2', '项待确认事项'] },
    admin: { label: 'ADMIN VIEW', tasks: ['让责任边界可以被复核。', '管理员关注角色、范围、审计和运行状态，业务正文按最小必要原则呈现。', '7', '项治理检查'] },
  };
  const modules = {
    tasks: ['把今天要做的事排在前面。', '以截止时间、责任人和当前状态组织任务，减少在多个页面之间来回查找。', '4', '项待处理任务'],
    requests: ['一条请求，走完提交到办结。', '通过版本、责任人和转介确认，把咨询、材料和补正过程留在同一条可读路径上。', '3', '个进行中请求'],
    knowledge: ['制度来源，和当前版本一起出现。', '检索结果携带来源、版本与更新时间；草稿可以辅助整理，但发布前仍需要人工核对。', '12', '条可检索制度'],
  };
  const roleButtons = [...document.querySelectorAll('.role')];
  const moduleButtons = [...document.querySelectorAll('.module')];
  const title = document.querySelector('#panel-title');
  const copy = document.querySelector('#panel-copy');
  const value = document.querySelector('#insight-value');
  const label = document.querySelector('#insight-label');
  const roleLabel = document.querySelector('#panel-role');
  const progress = document.querySelector('.progress span');
  let role = 'counselor';
  let module = 'tasks';
  function render() {
    const data = module === 'tasks' ? roles[role].tasks : modules[module];
    title.textContent = data[0]; copy.textContent = data[1]; value.textContent = data[2]; label.textContent = data[3];
    roleLabel.textContent = module === 'tasks' ? roles[role].label : `${roles[role].label} · ${module.toUpperCase()}`;
  }
  roleButtons.forEach((button) => button.addEventListener('click', () => { role = button.dataset.role; roleButtons.forEach((b) => b.classList.toggle('active', b === button)); render(); }));
  moduleButtons.forEach((button) => button.addEventListener('click', () => { module = button.dataset.module; moduleButtons.forEach((b) => b.classList.toggle('active', b === button)); render(); }));
  const lightbox = document.querySelector('.lightbox');
  const lightboxImage = lightbox.querySelector('img');
  const lightboxText = lightbox.querySelector('p');
  document.querySelectorAll('.shot').forEach((shot) => shot.addEventListener('click', () => { lightboxImage.src = shot.dataset.image; lightboxImage.alt = shot.dataset.alt; lightboxText.textContent = shot.dataset.alt; lightbox.showModal(); }));
  lightbox.querySelector('.close').addEventListener('click', () => lightbox.close());
  lightbox.addEventListener('click', (event) => { if (event.target === lightbox) lightbox.close(); });
  window.addEventListener('scroll', () => { const max = document.documentElement.scrollHeight - innerHeight; progress.style.transform = `scaleX(${max ? scrollY / max : 0})`; }, { passive: true });
  render();
})();

// Public showcase version of the counselor feature search. The production app
// uses the same interaction pattern: a keyboard shortcut, role-safe results,
// and a direct jump to the relevant work area.
(() => {
  const dialog = document.querySelector('.feature-search');
  const input = document.querySelector('#feature-search-input');
  const results = document.querySelector('#feature-search-results');
  const trigger = document.querySelector('.search-trigger');
  if (!dialog || !input || !results || !trigger) return;
  const features = [
    { title: '工作台', desc: '先看今天要做什么、任务进度和待办', target: '#overview' },
    { title: '内部聊天', desc: '与校内联系人沟通，发送文字和文件', target: '#flow' },
    { title: '学生材料收集', desc: '发起收集、查看提交、审核材料', target: '#evidence' },
    { title: '宿舍管理', desc: '床位分配、卫生统计和退宿处理', target: '#evidence' },
    { title: 'AI 写作', desc: '新闻初稿、文字优化和工作总结', target: '#evidence' },
    { title: '个人设置', desc: '修改密码、提醒时间和页面偏好', target: '#flow' },
  ];
  const normalize = value => value.trim().toLocaleLowerCase().replace(/\s+/g, '');
  function render() {
    const query = normalize(input.value);
    const list = features.filter(item => !query || normalize(`${item.title}${item.desc}`).includes(query));
    results.replaceChildren(...list.map(item => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'feature-result';
      button.innerHTML = `<strong>${item.title}</strong><span>${item.desc}</span><b>→</b>`;
      button.addEventListener('click', () => { dialog.close(); document.querySelector(item.target)?.scrollIntoView({ behavior: 'smooth' }); });
      return button;
    }));
    if (!list.length) {
      const empty = document.createElement('p');
      empty.className = 'feature-empty';
      empty.textContent = '没有找到匹配功能，试试“学生”“宿舍”或“AI”。';
      results.append(empty);
    }
  }
  function open() { dialog.showModal(); input.value = ''; render(); requestAnimationFrame(() => input.focus()); }
  trigger.addEventListener('click', open);
  input.addEventListener('input', render);
  document.addEventListener('keydown', event => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); open(); }
  });
})();
