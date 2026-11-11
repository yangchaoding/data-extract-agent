"""PaddleOCR-VL 识别结果的自动切片与结构化信息抽取。"""

from .agent import ExtractionAgent, ExtractionResult, FieldResult, Item
from .chunker import Chunk, chunk_document
from .document import Block, Document
from .llm import LLM, OpenAICompatLLM, llm_from_env
from .loader import document_from_result, load_document, load_documents
from .schema import Field, Schema, load_schema, schema_from_dict

__all__ = [
    "Block",
    "Chunk",
    "Document",
    "ExtractionAgent",
    "ExtractionResult",
    "Field",
    "FieldResult",
    "Item",
    "LLM",
    "OpenAICompatLLM",
    "Schema",
    "chunk_document",
    "document_from_result",
    "llm_from_env",
    "load_document",
    "load_documents",
    "load_schema",
    "schema_from_dict",
]
