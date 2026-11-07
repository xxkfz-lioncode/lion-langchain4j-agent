#!/usr/bin/env node
/**
 * 零依赖 MCP stdio 服务器(教学演示用)
 *
 * 不依赖任何 npm 包, 直接用 Node 18+ 手写 MCP 协议的 stdio 传输层:
 *   - 传输: stdin/stdout 按行读取/写出的 JSON-RPC 2.0 消息
 *   - 协议: 2025-11-25(显式握手, 不做版本探测)
 *   - 工具: echo(回显) / add(加法) / now(当前时间)
 *
 * 关键约定:
 *   - stdout 只允许出现协议消息, 任何日志都必须写到 stderr
 *   - 通知(无 id 的消息)不回响应
 *
 * 启动方式: node mcp-server/index.mjs
 */
import readline from 'node:readline';

const SERVER_INFO = { name: 'lion-demo-mcp-server', version: '1.0.0' };

// 工具定义: name / description / inputSchema(JSON Schema)
const TOOLS = [
  {
    name: 'echo',
    description: '把输入的文本原样返回, 用于验证工具链路是否打通',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: '要回显的文本' }
      },
      required: ['text']
    }
  },
  {
    name: 'add',
    description: '计算两个数字的和',
    inputSchema: {
      type: 'object',
      properties: {
        a: { type: 'number', description: '第一个加数' },
        b: { type: 'number', description: '第二个加数' }
      },
      required: ['a', 'b']
    }
  },
  {
    name: 'now',
    description: '获取服务器当前时间(ISO 8601 格式)',
    inputSchema: { type: 'object', properties: {} }
  }
];

/** 工具执行: 返回 { content: [{type:'text', text}] } */
function callTool(name, args) {
  switch (name) {
    case 'echo':
      return { content: [{ type: 'text', text: String(args?.text ?? '') }] };
    case 'add': {
      const a = Number(args?.a);
      const b = Number(args?.b);
      if (Number.isNaN(a) || Number.isNaN(b)) {
        return { content: [{ type: 'text', text: '参数必须是数字' }], isError: true };
      }
      return { content: [{ type: 'text', text: String(a + b) }] };
    }
    case 'now':
      return { content: [{ type: 'text', text: new Date().toISOString() }] };
    default:
      return { content: [{ type: 'text', text: `未知工具: ${name}` }], isError: true };
  }
}

/** 处理一条 JSON-RPC 消息; 返回响应对象, 通知类返回 null */
function handle(msg) {
  const { id, method, params } = msg;

  switch (method) {
    case 'initialize':
      // 握手: 回显客户端请求的协议版本(支持 2025-11-25), 声明工具能力
      return {
        jsonrpc: '2.0',
        id,
        result: {
          protocolVersion: params?.protocolVersion ?? '2025-11-25',
          capabilities: { tools: { listChanged: false } },
          serverInfo: SERVER_INFO
        }
      };

    case 'ping':
      return { jsonrpc: '2.0', id, result: {} };

    case 'tools/list':
      return { jsonrpc: '2.0', id, result: { tools: TOOLS } };

    case 'tools/call':
      return {
        jsonrpc: '2.0',
        id,
        result: callTool(params?.name, params?.arguments ?? {})
      };

    default:
      // 未知方法: 按 JSON-RPC 规范回 method not found
      if (id !== undefined) {
        return {
          jsonrpc: '2.0',
          id,
          error: { code: -32601, message: `Method not found: ${method}` }
        };
      }
      return null;
  }
}

const rl = readline.createInterface({ input: process.stdin, terminal: false });

rl.on('line', (line) => {
  const trimmed = line.trim();
  if (!trimmed) {
    return;
  }

  let msg;
  try {
    msg = JSON.parse(trimmed);
  } catch (e) {
    console.error('[mcp-demo] 无法解析的输入行:', trimmed, e.message);
    return;
  }

  // 协议流量日志(stdout 只能走协议, 所以日志一律写 stderr)
  console.error(`[mcp-demo] 收到 ${msg.method}${msg.id !== undefined ? ` (id=${msg.id})` : ' (通知)'}`
    + (msg.method === 'tools/call' ? ` params=${JSON.stringify(msg.params)}` : ''));

  const response = handle(msg);
  if (response) {
    // stdout 是协议通道: 一行一条 JSON-RPC 响应
    process.stdout.write(JSON.stringify(response) + '\n');
    console.error(`[mcp-demo] 响应 ${msg.method} (id=${msg.id})`);
  }
});

rl.on('close', () => {
  console.error('[mcp-demo] stdin 关闭, 退出');
  process.exit(0);
});

console.error('[mcp-demo] MCP stdio 服务器已启动, 工具:', TOOLS.map((t) => t.name).join(', '));
