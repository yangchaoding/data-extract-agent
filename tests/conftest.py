import pytest

from support import openai_server


@pytest.fixture
def server():
    # 不清理代理相关的环境变量：客户端对本机地址应当自己绕开代理
    with openai_server() as srv:
        yield srv
