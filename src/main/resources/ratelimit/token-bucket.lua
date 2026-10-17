-- Integer-credit token bucket. One token costs periodMs credits.
-- Redis scripts are atomic, not rollback transactions: validate and prepare everything
-- before the single final SET. In particular, rejected/invalid buckets are never repaired.
local function unavailable()
    error('Rate limiting unavailable', 0)
end

local function integer(value, minimum, maximum)
    return type(value) == 'number' and value == value
        and value >= minimum and value <= maximum and value == math.floor(value)
end

if #KEYS ~= 1 or #ARGV ~= 4 then unavailable() end
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local period = tonumber(ARGV[3])
local fingerprint = ARGV[4]
if not integer(capacity, 1, 1000000) or not integer(refill, 1, 1000000)
    or not integer(period, 1000, 86400000)
    or #fingerprint ~= 64 or not string.match(fingerprint, '^[0-9a-f]+$') then
    unavailable()
end

-- C <= 8.64e13; all credit intermediates stay below 2^53.
local ceiling = capacity * period
local fullRefill = math.ceil(ceiling / refill)
if fullRefill > 86400000 then unavailable() end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local credit = ceiling
local last = now
local raw = redis.call('GET', KEYS[1]) -- WRONGTYPE must fail closed, without repair.
if raw then
    if #raw > 512 or not string.match(raw, '^%s*{') then unavailable() end
    local ok, state = pcall(cjson.decode, raw)
    if not ok or type(state) ~= 'table' or state.schema ~= 1
        or state.fingerprint ~= fingerprint
        or not integer(state.credit, 0, ceiling)
        or not integer(state.updatedAtMs, 0, now + 60000) then
        unavailable()
    end
    if redis.call('PTTL', KEYS[1]) < 0 then unavailable() end
    credit = state.credit
    last = state.updatedAtMs
end

local elapsed = math.min(math.max(0, now - last), fullRefill)
local available = math.min(ceiling, credit + elapsed * refill)
local wait = math.max(0, last - now)
if available < period then
    return {0, math.max(1, wait + math.ceil((period - available) / refill))}
end

local nextCredit = available - period
local updated = math.max(now, last)
local ttl = math.max(1, wait + math.ceil((ceiling - nextCredit) / refill))
-- Preserve exact integer JSON without depending on cjson's 14-digit encoder precision.
-- The fingerprint is validated lowercase hex, so it needs no JSON escaping.
local payload = '{"schema":1,"fingerprint":"' .. fingerprint .. '","credit":'
    .. string.format('%.0f', nextCredit) .. ',"updatedAtMs":' .. string.format('%.0f', updated) .. '}'
local result = {1, 0}
redis.call('SET', KEYS[1], payload, 'PX', ttl)
return result
