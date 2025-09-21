# Development targets
check-format:
	sbt scalafmtCheckAll
	
format:
	sbt scalafmtAll

format-test:
	sbt scalafmtCheckAll

test:
	sbt "memctrl/test"