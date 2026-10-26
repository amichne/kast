package fixture.controls

class TestReceiverDispatch : TestOnlyReceiver {
    override fun preserveTest(): TestOnlyReceiver = this
}
