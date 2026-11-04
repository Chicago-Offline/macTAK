.PHONY: help hooks build build-angle angle test test-roundtrip test-render lint clean

help:
	@echo "Targets:"
	@echo "  hooks          - install git hooks (git config core.hooksPath .githooks)"
	@echo "  build          - build native + commo (dev/g1/build-native.sh, dev/g1/build-commo.sh)"
	@echo "  angle          - stage ANGLE (EGL/GLES on Metal) into dev/g2/angle-dist (dev/g2/fetch-angle.sh)"
	@echo "  build-angle    - build native with ANGLE in the GLES slot (MACTAK_GLES=angle) + commo"
	@echo "  test           - run smoke tests (dev/g1/smoke/run.sh)"
	@echo "  test-roundtrip - run headless round-trip tests (dev/g1/headless/run.sh)"
	@echo "  test-render    - G2 render smoke: engine GLGlobe into an EGL pbuffer on ANGLE (dev/g2/render/run.sh)"
	@echo "  lint           - shellcheck + bash -n over dev/**/*.sh; markdownlint/actionlint if installed"
	@echo "  clean          - remove build outputs"

hooks:
	git config core.hooksPath .githooks

build:
	bash dev/g1/build-native.sh && bash dev/g1/build-commo.sh

angle:
	bash dev/g2/fetch-angle.sh && bash dev/g2/smoke/run.sh

build-angle: angle
	MACTAK_GLES=angle bash dev/g1/build-native.sh && bash dev/g1/build-commo.sh

test:
	bash dev/g1/smoke/run.sh

test-roundtrip:
	bash dev/g1/headless/run.sh

test-render:
	bash dev/g2/render/run.sh

lint:
	@git ls-files -z 'dev/**/*.sh' | xargs -0 -n1 bash -n
	@if command -v shellcheck >/dev/null 2>&1; then \
		git ls-files -z 'dev/**/*.sh' | xargs -0 -n1 shellcheck; \
	else \
		echo "[lint] skip: shellcheck not installed"; \
	fi
	@if command -v markdownlint-cli2 >/dev/null 2>&1; then \
		markdownlint-cli2 '**/*.md'; \
	else \
		echo "[lint] skip: markdownlint-cli2 not installed"; \
	fi
	@if command -v actionlint >/dev/null 2>&1; then \
		actionlint; \
	else \
		echo "[lint] skip: actionlint not installed"; \
	fi

clean:
	rm -rf dev/g1/native-dist dev/g1/build-commo dev/g1/headless/build dev/g2/angle-dist dev/g2/build
