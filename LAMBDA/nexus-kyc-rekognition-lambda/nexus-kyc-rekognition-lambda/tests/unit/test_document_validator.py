import pytest
from unittest.mock import patch, MagicMock


class TestDocumentValidator:
    """Unit tests for document_validator.validate_document()."""

    @patch("validation.document_validator.s3")
    def test_valid_jpeg_passes(self, mock_s3):
        """A normal JPEG within size limits should pass."""
        mock_s3.head_object.return_value = {"ContentType": "image/jpeg"}
        from validation.document_validator import validate_document

        result = validate_document("nexus-kyc-documents", "kyc/user1/ver1/doc.jpg", 500_000)
        assert result["valid"] is True
        assert result["reasons"] == []

    @patch("validation.document_validator.s3")
    def test_empty_file_rejected(self, mock_s3):
        from validation.document_validator import validate_document

        result = validate_document("nexus-kyc-documents", "kyc/user1/ver1/doc.jpg", 0)
        assert result["valid"] is False
        assert any("EMPTY_FILE" in r for r in result["reasons"])

    @patch("validation.document_validator.s3")
    def test_oversized_file_rejected(self, mock_s3):
        from validation.document_validator import validate_document

        result = validate_document("nexus-kyc-documents", "kyc/user1/ver1/doc.jpg", 20_000_000)
        assert result["valid"] is False
        assert any("FILE_TOO_LARGE" in r for r in result["reasons"])

    @patch("validation.document_validator.s3")
    def test_tiny_file_rejected(self, mock_s3):
        from validation.document_validator import validate_document

        result = validate_document("nexus-kyc-documents", "kyc/user1/ver1/doc.jpg", 500)
        assert result["valid"] is False
        assert any("FILE_TOO_SMALL" in r for r in result["reasons"])

    @patch("validation.document_validator.s3")
    def test_unsupported_content_type_rejected(self, mock_s3):
        mock_s3.head_object.return_value = {"ContentType": "application/pdf"}
        from validation.document_validator import validate_document

        result = validate_document("nexus-kyc-documents", "kyc/user1/ver1/doc.jpg", 500_000)
        assert result["valid"] is False
        assert any("UNSUPPORTED_CONTENT_TYPE" in r for r in result["reasons"])
