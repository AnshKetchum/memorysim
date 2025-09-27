test:
	echo "Running unit tests"
	sbt "memctrl/test"

	echo "Running verilator sanity tests"
	$(MAKE) verilator-sanity-test
	$(MAKE) build-clean

	echo "Building verilator traces for testing"
	$(MAKE) verilator-trace
	pytest tests/ -v -s

	$(MAKE) build-clean