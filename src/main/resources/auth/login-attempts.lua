-- One atomic login-failure operation. No raw identifier or host clock is needed.
if #KEYS ~= 1 or #ARGV ~= 3 then error('Invalid login attempt arguments') end
local op = ARGV[1]
if op ~= 'check' and op ~= 'failure' and op ~= 'clear' then
    error('Invalid login attempt operation')
end
if ARGV[2] ~= '5' or not string.match(ARGV[3], '^[0-9]+$') then
    error('Invalid login attempt limits')
end
local ttl = tonumber(ARGV[3])
if not ttl or ttl < 1 or ttl > 900000 or ttl ~= math.floor(ttl) then
    error('Invalid login attempt TTL')
end

-- GET deliberately rejects wrong Redis types. Validate even when clearing:
-- authentication may reset valid state, but must never repair corruption.
local raw = redis.call('GET', KEYS[1])
local count = 0
local remaining = 0
if raw then
    if not string.match(raw, '^[1-5]$') then error('Invalid login attempt state') end
    count = tonumber(raw)
    remaining = redis.call('PTTL', KEYS[1])
    if remaining < 0 then error('Invalid login attempt expiry') end
end

local success = {1, 0}
if op == 'clear' then
    if raw then redis.call('DEL', KEYS[1]) end
    return success
end
if count == 5 then
    return {0, math.max(1, remaining)}
end
if op == 'check' then return success end

-- Prepare everything before the one final mutation. The fifth failure is still
-- recorded successfully; only subsequent check/failure operations are blocked.
local nextValue = tostring(count + 1)
redis.call('SET', KEYS[1], nextValue, 'PX', ttl)
return success
