-- KEYS[1] = 限流 key
-- ARGV[1] = 当前时间戳(毫秒)  ARGV[2] = 窗口(毫秒)  ARGV[3] = 最大请求数
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

-- 1.移除窗口外的过期请求
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
-- 2.统计窗口内请求数
local count = redis.call('ZCARD', key)
if count < limit then
    -- 3.放行：记录本次请求（member 加随机数保证唯一）
    redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
    -- 4.窗口结束后自动清理整个 key
    redis.call('PEXPIRE', key, window)
    return 1
end
return 0