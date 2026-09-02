# No module-specific keep rules.
#
# The kotlinx-serialization rules that matter live in verification-core's
# consumer-rules.pro and reach an integrator transitively through the core AAR.
# This file exists so the module's consumerProguardFiles declaration is real: a
# declared-but-absent file is a build error, and a module that silently has no
# consumer-rules hook is one refactor away from needing one and not having it.
