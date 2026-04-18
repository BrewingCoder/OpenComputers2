-- inv_test.lua — verify the inventory part end-to-end.
-- Run with: `run inv_test.lua` from the computer shell.

local function rule(label)
  print("== " .. label .. " ==")
end

rule("peripheral.find('inventory')")
local inv = peripheral.find("inventory")
if inv == nil then
  print("no inventory part found on this channel.")
  print("did you install an Inventory Part on an Adapter facing a chest?")
  return
end

print("kind  : " .. inv.kind)
print("name  : " .. inv.name)
print("slots : " .. inv.size())

rule("inv.list()")
local list = inv.list()
local nonEmpty = 0
for i = 1, #list do
  local s = list[i]
  if s then
    nonEmpty = nonEmpty + 1
    print(string.format("  [%2d] %s x%d", i, s.id, s.count))
  end
end
if nonEmpty == 0 then
  print("  (all slots empty — drop a few items in to see this populate)")
else
  print("  total stacks: " .. nonEmpty)
end

rule("peripheral.list('inventory')")
local all = peripheral.list("inventory")
print("found " .. #all .. " inventory part(s) on this channel:")
for i, p in ipairs(all) do
  print("  " .. i .. ". name=" .. p.name .. ", size=" .. p.size())
end

if #all >= 2 then
  rule("inv.push() — first → second")
  local src, dst = all[1], all[2]
  -- find the first non-empty slot on src
  local fromSlot = -1
  for i = 1, src.size() do
    if src.getItem(i) then fromSlot = i; break end
  end
  if fromSlot < 0 then
    print("source has no items to push; skipping move test")
  else
    local moved = src.push(fromSlot, dst, 1)
    print("moved " .. moved .. " item(s) from " .. src.name .. "[" .. fromSlot .. "] -> " .. dst.name)
  end
end

rule("done")
