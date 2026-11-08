package dev.jasagent.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PageController {
    @GetMapping(value = "/login", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String login() {
        return page("登录", """
                <form id="login">
                  <label>用户 <input name="user" autocomplete="username" required></label>
                  <label>口令 <input name="password" type="password" autocomplete="current-password" required></label>
                  <button type="submit">登录</button>
                  <p id="msg"></p>
                </form>
                <script>
                document.getElementById('login').onsubmit = async (event) => {
                  event.preventDefault();
                  const body = new URLSearchParams(new FormData(event.target));
                  const response = await fetch('/login', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body});
                  if (response.ok) location.href = '/';
                  else document.getElementById('msg').textContent = '登录失败';
                };
                </script>
                """);
    }

    @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String home() {
        return page("执行", """
                <h1>当前用户的执行</h1>
                <p><a href="/run">提交</a></p>
                <form id="workspace">
                  <label>工作区 <input name="path" required></label>
                  <button type="submit">选定工作区</button>
                </form>
                <ul id="list"></ul>
                <h2>检索</h2>
                <form id="search">
                  <label>查询 <input name="query" value="alpha-marker"></label>
                  <label>scope <input name="scopes" value="user"></label>
                  <button type="submit">rag.search</button>
                </form>
                <pre id="hits"></pre>
                <script>
                document.getElementById('workspace').onsubmit = async (event) => {
                  event.preventDefault();
                  const path = new FormData(event.target).get('path');
                  await fetch('/api/workspace', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({path})});
                  location.reload();
                };
                fetch('/api/executions').then(r => r.json()).then(rows => {
                  document.getElementById('list').innerHTML = rows.map(row => `<li><a href="/executions/${row.executionId}">${row.executionId}</a> ${row.status}</li>`).join('');
                });
                document.getElementById('search').onsubmit = async (event) => {
                  event.preventDefault();
                  const data = Object.fromEntries(new FormData(event.target).entries());
                  const response = await fetch('/api/rag/search', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({query: data.query, scopes: data.scopes.split(',')})});
                  document.getElementById('hits').textContent = JSON.stringify(await response.json(), null, 2);
                };
                </script>
                """);
    }

    @GetMapping(value = "/run", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String run() {
        return page("提交", """
                <h1>提交工作流</h1>
                <form id="run">
                  <label>工作流 <input name="workflowId" value="answer-with-tool"></label>
                  <label>输入 <textarea name="input">请回答</textarea></label>
                  <button type="submit">提交</button>
                </form>
                <script>
                document.getElementById('run').onsubmit = async (event) => {
                  event.preventDefault();
                  const data = Object.fromEntries(new FormData(event.target).entries());
                  const response = await fetch('/api/executions', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(data)});
                  const body = await response.json();
                  if (body.executionId) location.href = '/executions/' + body.executionId;
                };
                </script>
                """);
    }

    @GetMapping(value = "/executions/{id}", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String execution(@PathVariable("id") String id) {
        return page("执行详情", """
                <h1>执行 <span id="eid"></span></h1>
                <p>状态 <strong id="status"></strong></p>
                <p>
                  <button id="stop">中止</button>
                  <button id="go">恢复</button>
                </p>
                <h2>队列</h2>
                <pre id="queue"></pre>
                <h2>日志</h2>
                <pre id="logs"></pre>
                <h2>检索溯源</h2>
                <pre id="retrievals"></pre>
                <h2>通行证</h2>
                <pre id="passes"></pre>
                <h2>评估</h2>
                <pre id="evaluations"></pre>
                <h2>输出</h2>
                <pre id="output"></pre>
                <script>
                const id = location.pathname.split('/').pop();
                document.getElementById('eid').textContent = id;
                async function refresh() {
                  const detail = await (await fetch('/api/executions/' + id)).json();
                  document.getElementById('status').textContent = detail.status;
                  document.getElementById('queue').textContent = (detail.queue || []).join('\\n');
                  document.getElementById('logs').textContent = detail.logs || '';
                  document.getElementById('retrievals').textContent = JSON.stringify(detail.retrievals || [], null, 2);
                  document.getElementById('passes').textContent = JSON.stringify(detail.passes || [], null, 2);
                  document.getElementById('evaluations').textContent = JSON.stringify(detail.evaluations || [], null, 2);
                  document.getElementById('output').textContent = detail.output || '';
                }
                document.getElementById('stop').onclick = async () => { await fetch('/api/executions/' + id + '/interrupt', {method:'POST'}); refresh(); };
                document.getElementById('go').onclick = async () => { await fetch('/api/executions/' + id + '/resume', {method:'POST'}); refresh(); };
                refresh();
                setInterval(refresh, 800);
                if (window.EventSource) {
                  const source = new EventSource('/api/executions/' + id + '/events');
                  source.onmessage = refresh;
                  source.addEventListener('status', refresh);
                }
                </script>
                """.replace("EID", id));
    }

    private static String page(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <title>%s · JaSAgent</title>
                  <style>
                    body { font-family: sans-serif; margin: 2rem auto; max-width: 48rem; line-height: 1.5; }
                    form { display: grid; gap: 0.75rem; }
                    input, textarea, button { font: inherit; padding: 0.4rem 0.6rem; }
                    pre { background: #f4f4f4; padding: 0.8rem; white-space: pre-wrap; }
                    a { color: #0b57d0; }
                  </style>
                </head>
                <body>
                  <nav><a href="/">执行</a> · <a href="/run">提交</a></nav>
                  %s
                </body>
                </html>
                """.formatted(title, body);
    }
}
