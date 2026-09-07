-- 1.参数列表
--1.1.优惠券id
local voucherId = ARGV[1]
--1.2.用户id
local userId = ARGV[2]

-- 2.数据key
--2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
--2.2.订单key（一人一单判重集合）
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
--3.1.判断库存是否充足（防御 key 不存在，避免 tonumber(nil) 报错）
local stock = tonumber(redis.call('get', stockKey))
if stock == nil then
    return -1
end
if (stock <= 0) then
    --3.2.库存不足，返回 1
    return 1
end
--3.3.判断用户是否已下单
if (redis.call('sismember', orderKey, userId) == 1) then
    --3.4.重复下单，返回 2
    return 2
end
--3.5.扣库存
redis.call('incrby', stockKey, -1)
--3.6.记录下单用户（关单时 SREM 回补）
redis.call('sadd', orderKey, userId)
--3.7.资格预检通过，返回 0（订单落库由 RabbitMQ 异步完成）
return 0