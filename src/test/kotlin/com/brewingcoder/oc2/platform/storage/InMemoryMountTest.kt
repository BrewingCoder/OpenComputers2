package com.brewingcoder.oc2.platform.storage

class InMemoryMountTest : WritableMountContract() {
    override fun newMount(capacity: Long): WritableMount = InMemoryMount(capacity)
}
