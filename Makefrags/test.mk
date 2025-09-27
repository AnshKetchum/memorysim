unit-test:
	echo "Running unit tests"
	sbt "memctrl/test"

verilator-trace-tests:
	echo "Building verilator traces for testing"
	$(MAKE) verilator-trace
	pytest tests/ -v -s

test:
	$(MAKE) unit-test

	echo "Running verilator sanity tests"
	$(MAKE) verilator-sanity-test
	$(MAKE) build-clean

	$(MAKE) verilator-trace-tests

	$(MAKE) build-clean